package com.nicta.scoobi
package impl
package exec

import testing.mutable.UnitSpecification
import org.apache.hadoop.conf.Configuration
import java.io.ByteArrayOutputStream

class DistCacheSpec extends UnitSpecification {
  "it is possible to serialise a configuration object without its classloader" >> {
    val configuration = new Configuration
    val out = new ByteArrayOutputStream

    DistCache.serialise(configuration, configuration, out)
    out.toString must not contain("classLoader")
  }
}