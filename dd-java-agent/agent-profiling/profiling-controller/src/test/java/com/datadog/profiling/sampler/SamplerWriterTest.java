package com.datadog.profiling.sampler;

import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

public class SamplerWriterTest {
  @Test
  public void test() throws Exception {
    Path target = Paths.get("/tmp", "sampler.jfr");

    com.sun.management.ThreadMXBean bean =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    SamplerWriter writer = new SamplerWriter();

    for (int j = 0; j < 4; j++) {
      for (int i = 0; i < 15; i++) {
        for (ThreadInfo ti : bean.dumpAllThreads(false, false)) {
          writer.writeThreadSample(ti);
        }
        Thread.sleep(ThreadLocalRandom.current().nextLong(150L) + 10);
      }

      writer.dump(target);

      // sanity check to make sure the recording is loaded
      IItemCollection events = JfrLoaderToolkit.loadEvents(target.toFile());
      assertTrue(events.apply(ItemFilters.type(SamplerWriter.EVENT_NAME)).hasItems());
    }
  }
}
