/*
 * Copyright (c) 2016, Red Hat, Inc. and/or its affiliates.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

/*
 * @test MXNotificationsFullGC
 * @summary Check that full GC notifications are reported on Shenandoah's full GCs
 * @run main/othervm -XX:+UseShenandoahGC -XX:+UnlockDiagnosticVMOptions -XX:ShenandoahGCHeuristics=passive -Xmx1g -Xms1g MXNotificationsFullGC
 */

import javax.management.*;
import java.lang.management.*;

public class MXNotificationsFullGC {

  static volatile boolean notified;
  static volatile Object sink;

  public static void main(String[] args) throws Exception {
    NotificationListener listener = new NotificationListener() {
      @Override
      public void handleNotification(Notification n, Object o) {
        if (n.getType().equals(com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
          notified = true;
        }
      }
    };

    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      ((NotificationEmitter) bean).addNotificationListener(listener, null, null);
    }

    // Allocate 4*100K*10K = 4G, enough to blow the 1G heap into full GC
    for (int c = 0; c < 10_000; c++) {
       sink = new int[100_000];
    }

    // GC notifications are asynchronous, wait a little
    Thread.sleep(1000);

    if (!notified) {
      throw new IllegalStateException("Should have been notified");
    }
  }
}


