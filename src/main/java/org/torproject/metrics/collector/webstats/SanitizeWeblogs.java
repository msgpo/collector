/* Copyright 2017--2020 The Tor Project
 * See LICENSE for licensing information */

package org.torproject.metrics.collector.webstats;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.groupingByConcurrent;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.summingLong;

import org.torproject.descriptor.DescriptorParseException;
import org.torproject.descriptor.Method;
import org.torproject.descriptor.WebServerAccessLog;
import org.torproject.metrics.collector.conf.Configuration;
import org.torproject.metrics.collector.conf.Key;
import org.torproject.metrics.collector.conf.SourceType;
import org.torproject.metrics.collector.cron.CollecTorMain;
import org.torproject.metrics.collector.persist.PersistenceUtils;
import org.torproject.metrics.collector.persist.WebServerAccessLogPersistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This module processes web-logs for CollecTor according to the weblog
 * sanitation specification published on metrics.torproject.org.
 */
public class SanitizeWeblogs extends CollecTorMain {

  private static final Logger logger =
      LoggerFactory.getLogger(SanitizeWeblogs.class);
  private static final int LIMIT = 2;

  private static final String WEBSTATS = "webstats";
  private Path outputDirectory;
  private Path recentDirectory;
  private Path processedWebstatsFile;

  private boolean limits;

  /**
   * Possibly privacy impacting data is replaced by dummy data producing a
   * log-file (or files) that confirm(s) to Apache's Combined Log Format.
   */
  public SanitizeWeblogs(Configuration conf) {
    super(conf);
    this.mapPathDescriptors.put("recent/webstats", WebServerAccessLog.class);
  }

  @Override
  public String module() {
    return WEBSTATS;
  }

  @Override
  protected String syncMarker() {
    return "Webstats";
  }

  @Override
  protected void startProcessing() {
    try {
      Files.createDirectories(this.config.getPath(Key.OutputPath));
      Files.createDirectories(this.config.getPath(Key.RecentPath));
      Files.createDirectories(this.config.getPath(Key.StatsPath));
      this.outputDirectory = this.config.getPath(Key.OutputPath);
      this.recentDirectory = this.config.getPath(Key.RecentPath);
      this.processedWebstatsFile = this.config.getPath(Key.StatsPath)
          .resolve("processed-webstats");
      this.limits = this.config.getBool(Key.WebstatsLimits);
      Set<SourceType> sources = this.config.getSourceTypeSet(
          Key.WebstatsSources);
      if (sources.contains(SourceType.Local)) {
        logger.info("Processing logs using batch value {}.", BATCH);
        Map<LogMetadata, Set<LocalDate>> previouslyProcessedWebstats
            = this.readProcessedWebstats();
        Map<LogMetadata, Set<LocalDate>> newlyProcessedWebstats
            = this.findCleanWrite(this.config.getPath(Key.WebstatsLocalOrigins),
            previouslyProcessedWebstats);
        this.writeProcessedWebstats(newlyProcessedWebstats);
        long cutOffMillis = System.currentTimeMillis()
            - 3L * 24L * 60L * 60L * 1000L;
        PersistenceUtils.cleanDirectory(this.config.getPath(Key.RecentPath),
            cutOffMillis);
      }
    } catch (Exception e) {
      logger.error("Cannot sanitize web-logs: {}", e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  private Map<LogMetadata, Set<LocalDate>> readProcessedWebstats() {
    Map<LogMetadata, Set<LocalDate>> processedWebstats = new HashMap<>();
    if (Files.exists(this.processedWebstatsFile)) {
      try {
        for (String line : Files.readAllLines(this.processedWebstatsFile)) {
          String[] lineParts = line.split(",", 2);
          Optional<LogMetadata> logMetadata
              = LogMetadata.create(Paths.get(lineParts[1]));
          if (logMetadata.isPresent()) {
            processedWebstats.putIfAbsent(logMetadata.get(), new HashSet<>());
            LocalDate containedLogDate = LocalDate.parse(lineParts[0]);
            processedWebstats.get(logMetadata.get()).add(containedLogDate);
          }
        }
      } catch (IOException e) {
        logger.error("Cannot read state file {}.", this.processedWebstatsFile,
            e);
      }
      logger.debug("Read state file containing {} log files.",
          processedWebstats.size());
    }
    return processedWebstats;
  }

  private Map<LogMetadata, Set<LocalDate>> findCleanWrite(Path dir,
      Map<LogMetadata, Set<LocalDate>> previouslyProcessedWebstats) {
    Map<LogMetadata, Set<LocalDate>> newlyProcessedWebstats = new HashMap<>();
    LogFileMap fileMapIn = new LogFileMap(dir);
    logger.info("Found log files for {} virtual hosts.", fileMapIn.size());
    for (Map.Entry<String,TreeMap<String,TreeMap<LocalDate,LogMetadata>>>
             virtualEntry : fileMapIn.entrySet()) {
      String virtualHost = virtualEntry.getKey();
      for (Map.Entry<String, TreeMap<LocalDate, LogMetadata>> physicalEntry
          : virtualEntry.getValue().entrySet()) {
        String physicalHost = physicalEntry.getKey();
        logger.info("Processing logs for {} on {}.", virtualHost, physicalHost);
        /* Go through current input log files for given virtual and physical
         * host, and either look up contained log dates from the last execution,
         * or parse files to memory now. */
        Map<LocalDate, Map<String, Long>> sanitizedLinesByDate
            = new HashMap<>();
        Set<LogMetadata> previouslyReadFiles = new HashSet<>();
        for (LogMetadata logMetadata : physicalEntry.getValue().values()) {
          Set<LocalDate> containedLogDates;
          if (previouslyProcessedWebstats.containsKey(logMetadata)) {
            containedLogDates = previouslyProcessedWebstats.get(logMetadata);
            for (LocalDate date : containedLogDates) {
              sanitizedLinesByDate.putIfAbsent(date, new TreeMap<>());
            }
            previouslyReadFiles.add(logMetadata);
          } else {
            containedLogDates = sanitizeWebstatsLog(sanitizedLinesByDate,
                logMetadata);
          }
          newlyProcessedWebstats.put(logMetadata, containedLogDates);
        }
        /* Determine log dates that are safe to be written to disk now and that
         * we didn't write to disk before. */
        Set<LocalDate> storeDates = new HashSet<>();
        LocalDate[] interval = determineInterval(sanitizedLinesByDate.keySet());
        for (LocalDate newDate : sanitizedLinesByDate.keySet()) {
          if (newDate.isAfter(interval[0]) && newDate.isBefore(interval[1])) {
            WebServerAccessLogPersistence walp
                = new WebServerAccessLogPersistence(
                new WebServerAccessLogImpl(virtualHost, physicalHost, newDate));
            Path outputPath = this.outputDirectory
                .resolve(walp.getStoragePath());
            if (!Files.exists(outputPath)) {
              storeDates.add(newDate);
            }
          }
        }
        /* Reprocess previously read files containing log dates that we're going
         * to write to disk below. */
        for (LogMetadata previouslyReadFile : previouslyReadFiles) {
          if (!Collections.disjoint(storeDates,
              newlyProcessedWebstats.get(previouslyReadFile))) {
            sanitizeWebstatsLog(sanitizedLinesByDate, previouslyReadFile);
          }
        }
        /* Write sanitized log files to disk. */
        sanitizedLinesByDate.entrySet().stream()
            .filter((entry) -> storeDates.contains(entry.getKey())).parallel()
            .forEach((entry) -> storeSortedAndForget(virtualHost, physicalHost,
              entry.getKey(), entry.getValue()));
      }
    }
    return newlyProcessedWebstats;
  }

  private Set<LocalDate> sanitizeWebstatsLog(
      Map<LocalDate, Map<String, Long>> sanitizedLinesByDate,
      LogMetadata logFile) {
    Map<LocalDate, Map<String, Long>> newlySanitizedLinesByDate
        = sanitzedLineStream(logFile);
    for (Map.Entry<LocalDate, Map<String, Long>> e
        : newlySanitizedLinesByDate.entrySet()) {
      sanitizedLinesByDate.putIfAbsent(e.getKey(), new TreeMap<>());
      Map<String, Long> newlySanitizedLines
          = sanitizedLinesByDate.get(e.getKey());
      for (Map.Entry<String, Long> e1 : e.getValue().entrySet()) {
        newlySanitizedLines.put(e1.getKey(),
            newlySanitizedLines.getOrDefault(e1.getKey(), 0L) + e1.getValue());
      }
    }
    return newlySanitizedLinesByDate.keySet();
  }

  private void storeSortedAndForget(String virtualHost, String physicalHost,
      LocalDate date, Map<String, Long> lineCounts) {
    String name = new StringJoiner(WebServerAccessLogImpl.SEP)
        .add(virtualHost).add(physicalHost)
        .add(WebServerAccessLogImpl.MARKER)
        .add(date.format(DateTimeFormatter.BASIC_ISO_DATE))
        .toString() + "." + FileType.XZ.name().toLowerCase();
    logger.debug("Storing {}.", name);
    Map<String, Long> retainedLines = new TreeMap<>(lineCounts);
    lineCounts.clear(); // not needed anymore
    try {
      WebServerAccessLogPersistence walp
          = new WebServerAccessLogPersistence(
          new WebServerAccessLogImpl(toCompressedBytes(retainedLines),
          new File(name), name));
      logger.debug("Storing {}.", name);
      walp.storeOut(this.outputDirectory.toString());
      walp.storeRecent(this.recentDirectory.toString());
    } catch (DescriptorParseException dpe) {
      logger.error("Cannot store log desriptor {}.", name, dpe);
    } catch (Throwable th) { // catch all else
      logger.error("Serious problem.  Cannot store log desriptor {}.", name,
          th);
    }
  }

  private static final int BATCH = 100_000;

  static byte[] toCompressedBytes(Map<String, Long> lines)
    throws DescriptorParseException {
    try (ByteArrayOutputStream baos =  new ByteArrayOutputStream();
         OutputStream os = FileType.XZ.outputStream(baos)) {
      for (Map.Entry<String, Long> entry : lines.entrySet()) {
        long count = entry.getValue();
        byte[] batch = null;
        while (count > 0) {
          if (count > BATCH) {
            if (null == batch) {
              batch = bytesFor(entry.getKey(), BATCH);
            }
            os.write(batch);
            count -= BATCH;
          } else {
            os.write(bytesFor(entry.getKey(), count));
            break;
          }
        }
      }
      os.flush();
      os.close();
      return baos.toByteArray();
    } catch (Exception ex) {
      throw new DescriptorParseException(ex.getMessage());
    }
  }

  public static byte[] bytesFor(String line, long times) {
    return Stream.iterate(line, UnaryOperator.identity()).limit(times)
        .collect(Collectors.joining("\n", "", "\n")).getBytes();
  }

  static Optional<WebServerAccessLogLine>
      sanitize(WebServerAccessLogLine logLine) {
    if (!logLine.isValid()
        || !(Method.GET == logLine.getMethod()
             || Method.HEAD == logLine.getMethod())
        || !logLine.getProtocol().startsWith("HTTP")
        || 400 == logLine.getResponse() || 404 == logLine.getResponse()) {
      return Optional.empty();
    }
    if (!logLine.getIp().startsWith("0.0.0.")) {
      logLine.setIp("0.0.0.0");
    }
    int queryStart = logLine.getRequest().indexOf("?");
    if (queryStart > 0) {
      logLine.setRequest(logLine.getRequest().substring(0, queryStart));
    }
    return Optional.of(logLine);
  }

  LocalDate[] determineInterval(Set<LocalDate> dates) {
    if (dates.isEmpty()) { // return the empty interval
      return new LocalDate[]{LocalDate.MAX, LocalDate.MIN};
    }
    SortedSet<LocalDate> sorted = new TreeSet<>(dates);
    if (this.limits) {
      for (int i = 0; i < LIMIT - 1; i++) {
        sorted.remove(sorted.last());
      }
    }
    if (sorted.isEmpty()) { // return the empty interval
      return new LocalDate[]{LocalDate.MAX, LocalDate.MIN};
    }
    if (!this.limits) {
      sorted.add(sorted.first().minusDays(1));
      sorted.add(sorted.last().plusDays(1));
    }
    return new LocalDate[]{sorted.first(), sorted.last()};
  }

  private static final int LISTLIMIT = Integer.MAX_VALUE / 2;

  private Map<LocalDate, Map<String, Long>>
      sanitzedLineStream(LogMetadata metadata) {
    logger.debug("Processing file {}.", metadata.path);
    try (BufferedReader br
        = new BufferedReader(new InputStreamReader(
         metadata.fileType.decompress(Files.newInputStream(metadata.path))))) {
      List<List<WebServerAccessLogLine>> lists = new ArrayList<>();
      List<WebServerAccessLogLine> currentList = new ArrayList<>();
      lists.add(currentList);
      String lineStr = br.readLine();
      int count = 0;
      while (null != lineStr) {
        WebServerAccessLogLine wsal = WebServerAccessLogLine.makeLine(lineStr);
        if (wsal.isValid()) {
          currentList.add(wsal);
          count++;
        }
        if (count >= LISTLIMIT) {
          currentList = new ArrayList<>();
          lists.add(currentList);
          count = 0;
        }
        lineStr = br.readLine();
      }
      br.close();
      return lists.parallelStream()
          .map(list -> list.stream()
              .map(SanitizeWeblogs::sanitize)
              .filter(Optional::isPresent)
              .map(Optional::get)
              .collect(groupingBy(WebServerAccessLogLine::getDate,
                  groupingBy(WebServerAccessLogLine::toLogString, counting()))))
          .flatMap(map -> map.entrySet().stream()).parallel()
          .collect(groupingByConcurrent(Map.Entry::getKey,
              reducing(Collections.emptyMap(), Map.Entry::getValue,
                (e1, e2) -> Stream.concat(e1.entrySet().stream(),
                    e2.entrySet().stream()).parallel()
                    .collect(groupingByConcurrent(Map.Entry::getKey,
                        summingLong(Map.Entry::getValue))))));
    } catch (Exception ex) {
      logger.debug("Skipping log-file {}.", metadata.path, ex);
    }
    return Collections.emptyMap();
  }

  private void writeProcessedWebstats(
      Map<LogMetadata, Set<LocalDate>> newlyProcessedWebstats) {
    try {
      if (!Files.exists(this.processedWebstatsFile.getParent())) {
        Files.createDirectories(this.processedWebstatsFile.getParent());
      }
      List<String> lines = new ArrayList<>();
      for (Map.Entry<LogMetadata, Set<LocalDate>> e
          : newlyProcessedWebstats.entrySet()) {
        for (LocalDate logLineDate : e.getValue()) {
          lines.add(String.format("%s,%s", logLineDate, e.getKey().path));
        }
      }
      Files.write(this.processedWebstatsFile, lines);
    } catch (IOException e) {
      logger.error("Cannot write state file {}.", this.processedWebstatsFile,
          e);
    }
    logger.debug("Wrote state file containing {} log files.",
        newlyProcessedWebstats.size());
  }
}

