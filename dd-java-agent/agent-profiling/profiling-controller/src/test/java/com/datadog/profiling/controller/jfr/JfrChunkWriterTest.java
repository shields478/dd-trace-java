package com.datadog.profiling.controller.jfr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.datadog.profiling.jfr.JfrWriter;
import com.datadog.profiling.jfr.Type;
import com.datadog.profiling.jfr.TypedValue;
import com.datadog.profiling.jfr.Types;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.IMCThread;
import org.openjdk.jmc.common.item.Attribute;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;

class JfrChunkWriterTest {

  public static final String EVENT_NAME = "sample event";
  public static final String EVENT_MSG = "Hello world";

  @Test
  void writeEvent() throws Exception {
    JfrWriter writer = new JfrWriter();

    Type customSimpleType =
        writer.registerType(
            "com.datadog.types.Simple",
            b -> {
              b.addField("message", Types.Builtin.STRING);
            });

    Type eventType =
        writer.registerEventType(
            "dd.SampleEvent",
            eventTypeBuilder -> {
              eventTypeBuilder
                  .addField("name", Types.Builtin.STRING)
                  .addField("message", customSimpleType);
            });

    TypedValue eventValue =
        eventType.asValue(
            access -> {
              access
                  .putField("startTime", System.currentTimeMillis() * 1_000_000L)
                  .putField("name", EVENT_NAME)
                  .putField("message", EVENT_MSG)
                  .putField(
                      "eventThread",
                      threadAccess -> {
                        threadAccess
                            .putField("osName", "Java AWT-0")
                            .putField("osThreadId", 41953L)
                            .putField("javaName", "AWT-0")
                            .putField("javaThreadId", 11L)
                            .putField(
                                "group",
                                groupAcess -> {
                                  groupAcess.putField("name", "Main AWT Group");
                                });
                      })
                  .putField(
                      "stackTrace",
                      builder -> {
                        builder
                            .putField("truncated", false)
                            .putField(
                                "frames",
                                frame1 -> {
                                  frame1
                                      .putField("type", "Interpreted")
                                      .putField(
                                          "method",
                                          method -> {
                                            method
                                                .putField(
                                                    "type",
                                                    classType -> {
                                                      classType
                                                          .putField(
                                                              "name", "com.datadoghq.test.Main")
                                                          .putField(
                                                              "package",
                                                              pkg -> {
                                                                pkg.putField(
                                                                    "name", "com.datadoghq.test");
                                                              })
                                                          .putField(
                                                              "modifiers",
                                                              Modifier.PUBLIC | Modifier.FINAL);
                                                    })
                                                .putField("name", "main")
                                                .putField("descriptor", "([Ljava/lang/String;)V")
                                                .putField(
                                                    "modifiers",
                                                    Modifier.PUBLIC
                                                        | Modifier.STATIC
                                                        | Modifier.FINAL);
                                          });
                                },
                                frame2 -> {
                                  frame2
                                      .putField("type", "JIT compiled")
                                      .putField(
                                          "method",
                                          method -> {
                                            method
                                                .putField(
                                                    "type",
                                                    classType -> {
                                                      classType
                                                          .putField(
                                                              "name", "com.datadoghq.test.Main")
                                                          .putField(
                                                              "package",
                                                              pkg -> {
                                                                pkg.putField(
                                                                    "name", "com.datadoghq.test");
                                                              })
                                                          .putField(
                                                              "modifiers",
                                                              Modifier.PUBLIC | Modifier.FINAL);
                                                    })
                                                .putField("name", "doit")
                                                .putField("descriptor", "(Ljava/lang/String;)V")
                                                .putField(
                                                    "modifiers",
                                                    Modifier.PRIVATE
                                                        | Modifier.STATIC
                                                        | Modifier.FINAL);
                                          });
                                });
                      });
            });

    byte[] data = writer.newChunk().writeEvent(eventValue).dump();

    Files.write(Paths.get("/tmp", "test.jfr"), data);

    // sanity check to make sure the recording is loaded
    IItemCollection events = JfrLoaderToolkit.loadEvents(new File("/tmp/test.jfr"));

    IAttribute<String> nameAttr = Attribute.attr("name", "name", UnitLookup.PLAIN_TEXT);
    IAttribute<String> msgAttr = Attribute.attr("message", "message", UnitLookup.PLAIN_TEXT);
    assertTrue(events.hasItems());
    events.forEach(
        iitem -> {
          IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor =
              JfrAttributes.EVENT_STACKTRACE.getAccessor(iitem.getType());
          IMemberAccessor<String, IItem> nameAcessor = nameAttr.getAccessor(iitem.getType());
          IMemberAccessor<String, IItem> msgAcessor = msgAttr.getAccessor(iitem.getType());
          IMemberAccessor<IMCThread, IItem> eventThreadAccessor =
              JfrAttributes.EVENT_THREAD.getAccessor(iitem.getType());
          iitem.forEach(
              item -> {
                assertEquals(EVENT_NAME, nameAcessor.getMember(item));
                assertEquals(EVENT_MSG, msgAcessor.getMember(item));
                assertNotNull(eventThreadAccessor.getMember(item));
                IMCStackTrace stackTrace = stackTraceAccessor.getMember(item);
                assertEquals(2, stackTrace.getFrames().size());
              });
        });
  }
}
