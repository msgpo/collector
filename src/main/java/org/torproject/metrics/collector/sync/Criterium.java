/* Copyright 2016--2020 The Tor Project
 * See LICENSE for licensing information */

package org.torproject.metrics.collector.sync;

/** Interface for decisions to be made in the sync-process. */
public interface Criterium<T> {

  /** Determine, if the given object of type T fulfills the Criterium. */
  boolean applies(T object);

}

