/*
 * Copyright (c) 2017, Red Hat, Inc. and/or its affiliates.
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

package sun.jvm.hotspot.gc.shenandoah;

import sun.jvm.hotspot.gc.shared.ContiguousSpace;
import sun.jvm.hotspot.types.CIntegerField;
import sun.jvm.hotspot.runtime.VM;
import sun.jvm.hotspot.types.Type;
import sun.jvm.hotspot.types.TypeDataBase;
import sun.jvm.hotspot.debugger.Address;

import java.util.Observable;
import java.util.Observer;


public class ShenandoahHeapRegion extends ContiguousSpace {
    // static int RegionSizeBytes;
    private static CIntegerField RegionSizeBytes;
    private static CIntegerField State;
    private static CIntegerField regionNumber;

    private static int empty_uncommitted;
    private static int empty_committed;
    private static int regular;
    private static int humongous_start;
    private static int humongous_cont;
    private static int pinned_humongous_start;
    private static int cset;
    private static int pinned;
    private static int pinned_cset;
    private static int trash;

    static {
        VM.registerVMInitializedObserver(new Observer() {
            public void update(Observable o, Object data) {
                initialize(VM.getVM().getTypeDataBase());
            }
        });
    }

    static private synchronized void initialize(TypeDataBase db) {
        Type type = db.lookupType("ShenandoahHeapRegion");

        RegionSizeBytes = type.getCIntegerField("RegionSizeBytes");
        State = type.getCIntegerField("_state");
        regionNumber = type.getCIntegerField("_region_number");

        empty_uncommitted = db.lookupIntConstant("ShenandoahHeapRegion::_empty_uncommitted");
        empty_committed = db.lookupIntConstant("ShenandoahHeapRegion::_empty_committed");
        regular = db.lookupIntConstant("ShenandoahHeapRegion::_regular");
        humongous_start = db.lookupIntConstant("ShenandoahHeapRegion::_humongous_start");
        humongous_cont = db.lookupIntConstant("ShenandoahHeapRegion::_humongous_cont");
        pinned_humongous_start = db.lookupIntConstant("ShenandoahHeapRegion::_pinned_humongous_start");
        cset = db.lookupIntConstant("ShenandoahHeapRegion::_cset");
        pinned_cset = db.lookupIntConstant("ShenandoahHeapRegion::_pinned_cset");
        trash = db.lookupIntConstant("ShenandoahHeapRegion::_trash");
    }

    public static long regionSizeBytes() { return RegionSizeBytes.getValue(); }

    public long regionNumber() { return regionNumber.getValue(addr); }

    public boolean isUncommitted()      { return state() == empty_uncommitted; }
    public boolean isEmpty()            { return state() == empty_committed; }
    public boolean isRegular()          { return state() == regular; }
    public boolean isHumongousStart()   { return state() == humongous_start; }
    public boolean isHumongousCont()    { return state() == humongous_cont;  }
    public boolean isPinnedHumongous()  { return state() == pinned_humongous_start; }
    public boolean isCSet()             { return state() == cset; }
    public boolean isPinned()           { return state() == pinned; }
    public boolean isPinnedCset()       { return state() == pinned_cset; }
    public boolean isTrash()            { return state() == trash; }

    public ShenandoahHeapRegion(Address addr) {
        super(addr);
    }

    public int state() { return (int)State.getValue(addr); }
}
