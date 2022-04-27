/*
 * Copyright (c) 1999, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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

// no precompiled headers
#include "jvm.h"
#include "asm/macroAssembler.hpp"
#include "classfile/classLoader.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmSymbols.hpp"
#include "code/codeCache.hpp"
#include "code/icBuffer.hpp"
#include "code/vtableStubs.hpp"
#include "interpreter/interpreter.hpp"
#include "logging/log.hpp"
#include "memory/allocation.inline.hpp"
#include "os_share_linux.hpp"
#include "prims/jniFastGetField.hpp"
#include "prims/jvm_misc.hpp"
#include "runtime/arguments.hpp"
#include "runtime/extendedPC.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/java.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/osThread.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/thread.inline.hpp"
#include "runtime/timer.hpp"
#include "services/memTracker.hpp"
#include "utilities/align.hpp"
#include "utilities/debug.hpp"
#include "utilities/events.hpp"
#include "utilities/vmError.hpp"

// put OS-includes here
# include <sys/types.h>
# include <sys/mman.h>
# include <pthread.h>
# include <signal.h>
# include <errno.h>
# include <dlfcn.h>
# include <stdlib.h>
# include <stdio.h>
# include <unistd.h>
# include <sys/resource.h>
# include <pthread.h>
# include <sys/stat.h>
# include <sys/time.h>
# include <sys/utsname.h>
# include <sys/socket.h>
# include <sys/wait.h>
# include <pwd.h>
# include <poll.h>
# include <ucontext.h>
#ifndef AMD64
# include <fpu_control.h>
#endif

#ifdef AMD64
#define REG_SP REG_RSP
#define REG_PC REG_RIP
#define REG_FP REG_RBP
#define SPELL_REG_SP "rsp"
#define SPELL_REG_FP "rbp"
#else
#define REG_SP REG_UESP
#define REG_PC REG_EIP
#define REG_FP REG_EBP
#define SPELL_REG_SP "esp"
#define SPELL_REG_FP "ebp"
#endif // AMD64

address os::current_stack_pointer() {
#ifdef SPARC_WORKS
  void *esp;
  __asm__("mov %%" SPELL_REG_SP ", %0":"=r"(esp));
  return (address) ((char*)esp + sizeof(long)*2);
#else
  return (address)__builtin_frame_address(0);
#endif
}

char* os::non_memory_address_word() {
  // Must never look like an address returned by reserve_memory,
  // even in its subfields (as defined by the CPU immediate fields,
  // if the CPU splits constants across multiple instructions).

  return (char*) -1;
}

address os::Linux::ucontext_get_pc(const ucontext_t * uc) {
  return (address)uc->uc_mcontext.gregs[REG_PC];
}

void os::Linux::ucontext_set_pc(ucontext_t * uc, address pc) {
  uc->uc_mcontext.gregs[REG_PC] = (intptr_t)pc;
}

intptr_t* os::Linux::ucontext_get_sp(const ucontext_t * uc) {
  return (intptr_t*)uc->uc_mcontext.gregs[REG_SP];
}

intptr_t* os::Linux::ucontext_get_fp(const ucontext_t * uc) {
  return (intptr_t*)uc->uc_mcontext.gregs[REG_FP];
}

// For Forte Analyzer AsyncGetCallTrace profiling support - thread
// is currently interrupted by SIGPROF.
// os::Solaris::fetch_frame_from_ucontext() tries to skip nested signal
// frames. Currently we don't do that on Linux, so it's the same as
// os::fetch_frame_from_context().
// This method is also used for stack overflow signal handling.
ExtendedPC os::Linux::fetch_frame_from_ucontext(Thread* thread,
  const ucontext_t* uc, intptr_t** ret_sp, intptr_t** ret_fp) {

  assert(thread != NULL, "just checking");
  assert(ret_sp != NULL, "just checking");
  assert(ret_fp != NULL, "just checking");

  return os::fetch_frame_from_context(uc, ret_sp, ret_fp);
}

ExtendedPC os::fetch_frame_from_context(const void* ucVoid,
                    intptr_t** ret_sp, intptr_t** ret_fp) {

  ExtendedPC  epc;
  const ucontext_t* uc = (const ucontext_t*)ucVoid;

  if (uc != NULL) {
    epc = ExtendedPC(os::Linux::ucontext_get_pc(uc));
    if (ret_sp) *ret_sp = os::Linux::ucontext_get_sp(uc);
    if (ret_fp) *ret_fp = os::Linux::ucontext_get_fp(uc);
  } else {
    // construct empty ExtendedPC for return value checking
    epc = ExtendedPC(NULL);
    if (ret_sp) *ret_sp = (intptr_t *)NULL;
    if (ret_fp) *ret_fp = (intptr_t *)NULL;
  }

  return epc;
}

frame os::fetch_frame_from_context(const void* ucVoid) {
  intptr_t* sp;
  intptr_t* fp;
  ExtendedPC epc = fetch_frame_from_context(ucVoid, &sp, &fp);
  return frame(sp, fp, epc.pc());
}

frame os::fetch_frame_from_ucontext(Thread* thread, void* ucVoid) {
  intptr_t* sp;
  intptr_t* fp;
  ExtendedPC epc = os::Linux::fetch_frame_from_ucontext(thread, (ucontext_t*)ucVoid, &sp, &fp);
  return frame(sp, fp, epc.pc());
}

bool os::Linux::get_frame_at_stack_banging_point(JavaThread* thread, ucontext_t* uc, frame* fr) {
  address pc = (address) os::Linux::ucontext_get_pc(uc);
  if (Interpreter::contains(pc)) {
    // interpreter performs stack banging after the fixed frame header has
    // been generated while the compilers perform it before. To maintain
    // semantic consistency between interpreted and compiled frames, the
    // method returns the Java sender of the current frame.
    *fr = os::fetch_frame_from_ucontext(thread, uc);
    if (!fr->is_first_java_frame()) {
      // get_frame_at_stack_banging_point() is only called when we
      // have well defined stacks so java_sender() calls do not need
      // to assert safe_for_sender() first.
      *fr = fr->java_sender();
    }
  } else {
    // more complex code with compiled code
    assert(!Interpreter::contains(pc), "Interpreted methods should have been handled above");
    CodeBlob* cb = CodeCache::find_blob(pc);
    if (cb == NULL || !cb->is_nmethod() || cb->is_frame_complete_at(pc)) {
      // Not sure where the pc points to, fallback to default
      // stack overflow handling
      return false;
    } else {
      // in compiled code, the stack banging is performed just after the return pc
      // has been pushed on the stack
      intptr_t* fp = os::Linux::ucontext_get_fp(uc);
      intptr_t* sp = os::Linux::ucontext_get_sp(uc);
      *fr = frame(sp + 1, fp, (address)*sp);
      if (!fr->is_java_frame()) {
        assert(!fr->is_first_frame(), "Safety check");
        // See java_sender() comment above.
        *fr = fr->java_sender();
      }
    }
  }
  assert(fr->is_java_frame(), "Safety check");
  return true;
}

// By default, gcc always save frame pointer (%ebp/%rbp) on stack. It may get
// turned off by -fomit-frame-pointer,
frame os::get_sender_for_C_frame(frame* fr) {
  return frame(fr->sender_sp(), fr->link(), fr->sender_pc());
}

intptr_t* _get_previous_fp() {
#ifdef SPARC_WORKS
  intptr_t **ebp;
  __asm__("mov %%" SPELL_REG_FP ", %0":"=r"(ebp));
#elif defined(__clang__)
  intptr_t **ebp;
  __asm__ __volatile__ ("mov %%" SPELL_REG_FP ", %0":"=r"(ebp):);
#else
  register intptr_t **ebp __asm__ (SPELL_REG_FP);
#endif
  // ebp is for this frame (_get_previous_fp). We want the ebp for the
  // caller of os::current_frame*(), so go up two frames. However, for
  // optimized builds, _get_previous_fp() will be inlined, so only go
  // up 1 frame in that case.
#ifdef _NMT_NOINLINE_
  return **(intptr_t***)ebp;
#else
  return *ebp;
#endif
}


frame os::current_frame() {
  intptr_t* fp = _get_previous_fp();
  frame myframe((intptr_t*)os::current_stack_pointer(),
                (intptr_t*)fp,
                CAST_FROM_FN_PTR(address, os::current_frame));
  if (os::is_first_C_frame(&myframe)) {
    // stack is not walkable
    return frame();
  } else {
    return os::get_sender_for_C_frame(&myframe);
  }
}

// Utility functions

// From IA32 System Programming Guide
enum {
  trap_page_fault = 0xE
};

extern "C" JNIEXPORT int
JVM_handle_linux_signal(int sig,
                        siginfo_t* info,
                        void* ucVoid,
                        int abort_if_unrecognized) {
  ucontext_t* uc = (ucontext_t*) ucVoid;

  Thread* t = Thread::current_or_null_safe();

  // Must do this before SignalHandlerMark, if crash protection installed we will longjmp away
  // (no destructors can be run)
  os::ThreadCrashProtection::check_crash_protection(sig, t);

  SignalHandlerMark shm(t);

  // Note: it's not uncommon that JNI code uses signal/sigset to install
  // then restore certain signal handler (e.g. to temporarily block SIGPIPE,
  // or have a SIGILL handler when detecting CPU type). When that happens,
  // JVM_handle_linux_signal() might be invoked with junk info/ucVoid. To
  // avoid unnecessary crash when libjsig is not preloaded, try handle signals
  // that do not require siginfo/ucontext first.

  if (sig == SIGPIPE || sig == SIGXFSZ) {
    // allow chained handler to go first
    if (os::Linux::chained_handler(sig, info, ucVoid)) {
      return true;
    } else {
      // Ignoring SIGPIPE/SIGXFSZ - see bugs 4229104 or 6499219
      return true;
    }
  }

#ifdef CAN_SHOW_REGISTERS_ON_ASSERT
  if ((sig == SIGSEGV || sig == SIGBUS) && info != NULL && info->si_addr == g_assert_poison) {
    if (handle_assert_poison_fault(ucVoid, info->si_addr)) {
      return 1;
    }
  }
#endif

  JavaThread* thread = NULL;
  VMThread* vmthread = NULL;
  if (os::Linux::signal_handlers_are_installed) {
    if (t != NULL ){
      if(t->is_Java_thread()) {
        thread = (JavaThread*)t;
      }
      else if(t->is_VM_thread()){
        vmthread = (VMThread *)t;
      }
    }
  }

  // Handle SafeFetch faults:
  if (uc != NULL) {
    address const pc = (address) os::Linux::ucontext_get_pc(uc);
    if (pc && StubRoutines::is_safefetch_fault(pc)) {
      os::Linux::ucontext_set_pc(uc, StubRoutines::continuation_for_safefetch_fault(pc));
      return 1;
    }
  }

/*
  NOTE: does not seem to work on linux.
  if (info == NULL || info->si_code <= 0 || info->si_code == SI_NOINFO) {
    // can't decode this kind of signal
    info = NULL;
  } else {
    assert(sig == info->si_signo, "bad siginfo");
  }
*/
  // decide if this trap can be handled by a stub
  address stub = NULL;

  address pc          = NULL;

  //%note os_trap_1
  if (info != NULL && uc != NULL && thread != NULL) {
    pc = (address) os::Linux::ucontext_get_pc(uc);

#ifndef AMD64
    // Halt if SI_KERNEL before more crashes get misdiagnosed as Java bugs
    // This can happen in any running code (currently more frequently in
    // interpreter code but has been seen in compiled code)
    if (sig == SIGSEGV && info->si_addr == 0 && info->si_code == SI_KERNEL) {
      fatal("An irrecoverable SI_KERNEL SIGSEGV has occurred due "
            "to unstable signal handling in this distribution.");
    }
#endif // AMD64

    // Handle ALL stack overflow variations here
    if (sig == SIGSEGV) {
      address addr = (address) info->si_addr;

      // check if fault address is within thread stack
      if (thread->on_local_stack(addr)) {
        // stack overflow
        if (thread->in_stack_yellow_reserved_zone(addr)) {
          if (thread->thread_state() == _thread_in_Java) {
            if (thread->in_stack_reserved_zone(addr)) {
              frame fr;
              if (os::Linux::get_frame_at_stack_banging_point(thread, uc, &fr)) {
                assert(fr.is_java_frame(), "Must be a Java frame");
                frame activation =
                  SharedRuntime::look_for_reserved_stack_annotated_method(thread, fr);
                if (activation.sp() != NULL) {
                  thread->disable_stack_reserved_zone();
                  if (activation.is_interpreted_frame()) {
                    thread->set_reserved_stack_activation((address)(
                      activation.fp() + frame::interpreter_frame_initial_sp_offset));
                  } else {
                    thread->set_reserved_stack_activation((address)activation.unextended_sp());
                  }
                  return 1;
                }
              }
            }
            // Throw a stack overflow exception.  Guard pages will be reenabled
            // while unwinding the stack.
            thread->disable_stack_yellow_reserved_zone();
            stub = SharedRuntime::continuation_for_implicit_exception(thread, pc, SharedRuntime::STACK_OVERFLOW);
          } else {
            // Thread was in the vm or native code.  Return and try to finish.
            thread->disable_stack_yellow_reserved_zone();
            return 1;
          }
        } else if (thread->in_stack_red_zone(addr)) {
          // Fatal red zone violation.  Disable the guard pages and fall through
          // to handle_unexpected_exception way down below.
          thread->disable_stack_red_zone();
          tty->print_raw_cr("An irrecoverable stack overflow has occurred.");

          // This is a likely cause, but hard to verify. Let's just print
          // it as a hint.
          tty->print_raw_cr("Please check if any of your loaded .so files has "
                            "enabled executable stack (see man page execstack(8))");
        } else {
          // Accessing stack address below sp may cause SEGV if current
          // thread has MAP_GROWSDOWN stack. This should only happen when
          // current thread was created by user code with MAP_GROWSDOWN flag
          // and then attached to VM. See notes in os_linux.cpp.
          if (thread->osthread()->expanding_stack() == 0) {
             thread->osthread()->set_expanding_stack();
             if (os::Linux::manually_expand_stack(thread, addr)) {
               thread->osthread()->clear_expanding_stack();
               return 1;
             }
             thread->osthread()->clear_expanding_stack();
          } else {
             fatal("recursive segv. expanding stack.");
          }
        }
      }
    }

    if ((sig == SIGSEGV) && VM_Version::is_cpuinfo_segv_addr(pc)) {
      // Verify that OS save/restore AVX registers.
      stub = VM_Version::cpuinfo_cont_addr();
    }

    if (thread->thread_state() == _thread_in_Java) {
      // Java thread running in Java code => find exception handler if any
      // a fault inside compiled code, the interpreter, or a stub

      if (sig == SIGSEGV && os::is_poll_address((address)info->si_addr)) {
        stub = SharedRuntime::get_poll_stub(pc);
      } else if (sig == SIGBUS /* && info->si_code == BUS_OBJERR */) {
        // BugId 4454115: A read from a MappedByteBuffer can fault
        // here if the underlying file has been truncated.
        // Do not crash the VM in such a case.
        CodeBlob* cb = CodeCache::find_blob_unsafe(pc);
        CompiledMethod* nm = (cb != NULL) ? cb->as_compiled_method_or_null() : NULL;
        if (nm != NULL && nm->has_unsafe_access()) {
          address next_pc = Assembler::locate_next_instruction(pc);
          stub = SharedRuntime::handle_unsafe_access(thread, next_pc);
        }
      }
      else

#ifdef AMD64
      if (sig == SIGFPE  &&
          (info->si_code == FPE_INTDIV || info->si_code == FPE_FLTDIV)) {
        stub =
          SharedRuntime::
          continuation_for_implicit_exception(thread,
                                              pc,
                                              SharedRuntime::
                                              IMPLICIT_DIVIDE_BY_ZERO);
#else
      if (sig == SIGFPE /* && info->si_code == FPE_INTDIV */) {
        // HACK: si_code does not work on linux 2.2.12-20!!!
        int op = pc[0];
        if (op == 0xDB) {
          // FIST
          // TODO: The encoding of D2I in x86_32.ad can cause an exception
          // prior to the fist instruction if there was an invalid operation
          // pending. We want to dismiss that exception. From the win_32
          // side it also seems that if it really was the fist causing
          // the exception that we do the d2i by hand with different
          // rounding. Seems kind of weird.
          // NOTE: that we take the exception at the NEXT floating point instruction.
          assert(pc[0] == 0xDB, "not a FIST opcode");
          assert(pc[1] == 0x14, "not a FIST opcode");
          assert(pc[2] == 0x24, "not a FIST opcode");
          return true;
        } else if (op == 0xF7) {
          // IDIV
          stub = SharedRuntime::continuation_for_implicit_exception(thread, pc, SharedRuntime::IMPLICIT_DIVIDE_BY_ZERO);
        } else {
          // TODO: handle more cases if we are using other x86 instructions
          //   that can generate SIGFPE signal on linux.
          tty->print_cr("unknown opcode 0x%X with SIGFPE.", op);
          fatal("please update this code.");
        }
#endif // AMD64
      } else if (sig == SIGSEGV &&
               !MacroAssembler::needs_explicit_null_check((intptr_t)info->si_addr)) {
          // Determination of interpreter/vtable stub/compiled code null exception
          stub = SharedRuntime::continuation_for_implicit_exception(thread, pc, SharedRuntime::IMPLICIT_NULL);
      }
    } else if (thread->thread_state() == _thread_in_vm &&
               sig == SIGBUS && /* info->si_code == BUS_OBJERR && */
               thread->doing_unsafe_access()) {
        address next_pc = Assembler::locate_next_instruction(pc);
        stub = SharedRuntime::handle_unsafe_access(thread, next_pc);
    }

    // jni_fast_Get<Primitive>Field can trap at certain pc's if a GC kicks in
    // and the heap gets shrunk before the field access.
    if ((sig == SIGSEGV) || (sig == SIGBUS)) {
      address addr = JNI_FastGetField::find_slowcase_pc(pc);
      if (addr != (address)-1) {
        stub = addr;
      }
    }

    // Check to see if we caught the safepoint code in the
    // process of write protecting the memory serialization page.
    // It write enables the page immediately after protecting it
    // so we can just return to retry the write.
    if ((sig == SIGSEGV) &&
        os::is_memory_serialize_page(thread, (address) info->si_addr)) {
      // Block current thread until the memory serialize page permission restored.
      os::block_on_serialize_page_trap();
      return true;
    }
  }

#ifndef AMD64
  // Execution protection violation
  //
  // This should be kept as the last step in the triage.  We don't
  // have a dedicated trap number for a no-execute fault, so be
  // conservative and allow other handlers the first shot.
  //
  // Note: We don't test that info->si_code == SEGV_ACCERR here.
  // this si_code is so generic that it is almost meaningless; and
  // the si_code for this condition may change in the future.
  // Furthermore, a false-positive should be harmless.
  if (UnguardOnExecutionViolation > 0 &&
      (sig == SIGSEGV || sig == SIGBUS) &&
      uc->uc_mcontext.gregs[REG_TRAPNO] == trap_page_fault) {
    int page_size = os::vm_page_size();
    address addr = (address) info->si_addr;
    address pc = os::Linux::ucontext_get_pc(uc);
    // Make sure the pc and the faulting address are sane.
    //
    // If an instruction spans a page boundary, and the page containing
    // the beginning of the instruction is executable but the following
    // page is not, the pc and the faulting address might be slightly
    // different - we still want to unguard the 2nd page in this case.
    //
    // 15 bytes seems to be a (very) safe value for max instruction size.
    bool pc_is_near_addr =
      (pointer_delta((void*) addr, (void*) pc, sizeof(char)) < 15);
    bool instr_spans_page_boundary =
      (align_down((intptr_t) pc ^ (intptr_t) addr,
                       (intptr_t) page_size) > 0);

    if (pc == addr || (pc_is_near_addr && instr_spans_page_boundary)) {
      static volatile address last_addr =
        (address) os::non_memory_address_word();

      // In conservative mode, don't unguard unless the address is in the VM
      if (addr != last_addr &&
          (UnguardOnExecutionViolation > 1 || os::address_is_in_vm(addr))) {

        // Set memory to RWX and retry
        address page_start = align_down(addr, page_size);
        bool res = os::protect_memory((char*) page_start, page_size,
                                      os::MEM_PROT_RWX);

        log_debug(os)("Execution protection violation "
                      "at " INTPTR_FORMAT
                      ", unguarding " INTPTR_FORMAT ": %s, errno=%d", p2i(addr),
                      p2i(page_start), (res ? "success" : "failed"), errno);
        stub = pc;

        // Set last_addr so if we fault again at the same address, we don't end
        // up in an endless loop.
        //
        // There are two potential complications here.  Two threads trapping at
        // the same address at the same time could cause one of the threads to
        // think it already unguarded, and abort the VM.  Likely very rare.
        //
        // The other race involves two threads alternately trapping at
        // different addresses and failing to unguard the page, resulting in
        // an endless loop.  This condition is probably even more unlikely than
        // the first.
        //
        // Although both cases could be avoided by using locks or thread local
        // last_addr, these solutions are unnecessary complication: this
        // handler is a best-effort safety net, not a complete solution.  It is
        // disabled by default and should only be used as a workaround in case
        // we missed any no-execute-unsafe VM code.

        last_addr = addr;
      }
    }
  }
#endif // !AMD64

  if (stub != NULL) {
    // save all thread context in case we need to restore it
    if (thread != NULL) thread->set_saved_exception_pc(pc);

    os::Linux::ucontext_set_pc(uc, stub);
    return true;
  }

  // signal-chaining
  if (os::Linux::chained_handler(sig, info, ucVoid)) {
     return true;
  }

  if (!abort_if_unrecognized) {
    // caller wants another chance, so give it to him
    return false;
  }

  if (pc == NULL && uc != NULL) {
    pc = os::Linux::ucontext_get_pc(uc);
  }

  // unmask current signal
  sigset_t newset;
  sigemptyset(&newset);
  sigaddset(&newset, sig);
  sigprocmask(SIG_UNBLOCK, &newset, NULL);

  VMError::report_and_die(t, sig, pc, info, ucVoid);

  ShouldNotReachHere();
  return true; // Mute compiler
}

void os::Linux::init_thread_fpu_state(void) {
#ifndef AMD64
  // set fpu to 53 bit precision
  set_fpu_control_word(0x27f);
#endif // !AMD64
}

int os::Linux::get_fpu_control_word(void) {
#ifdef AMD64
  return 0;
#else
  int fpu_control;
  _FPU_GETCW(fpu_control);
  return fpu_control & 0xffff;
#endif // AMD64
}

void os::Linux::set_fpu_control_word(int fpu_control) {
#ifndef AMD64
  _FPU_SETCW(fpu_control);
#endif // !AMD64
}

// Check that the linux kernel version is 2.4 or higher since earlier
// versions do not support SSE without patches.
bool os::supports_sse() {
#ifdef AMD64
  return true;
#else
  struct utsname uts;
  if( uname(&uts) != 0 ) return false; // uname fails?
  char *minor_string;
  int major = strtol(uts.release,&minor_string,10);
  int minor = strtol(minor_string+1,NULL,10);
  bool result = (major > 2 || (major==2 && minor >= 4));
  log_info(os)("OS version is %d.%d, which %s support SSE/SSE2",
               major,minor, result ? "DOES" : "does NOT");
  return result;
#endif // AMD64
}

juint os::cpu_microcode_revision() {
  juint result = 0;
  char data[2048] = {0}; // lines should fit in 2K buf
  size_t len = sizeof(data);
  FILE *fp = fopen("/proc/cpuinfo", "r");
  if (fp) {
    while (!feof(fp)) {
      if (fgets(data, len, fp)) {
        if (strstr(data, "microcode") != NULL) {
          char* rev = strchr(data, ':');
          if (rev != NULL) sscanf(rev + 1, "%x", &result);
          break;
        }
      }
    }
    fclose(fp);
  }
  return result;
}

bool os::is_allocatable(size_t bytes) {
#ifdef AMD64
  // unused on amd64?
  return true;
#else

  if (bytes < 2 * G) {
    return true;
  }

  char* addr = reserve_memory(bytes, NULL);

  if (addr != NULL) {
    release_memory(addr, bytes);
  }

  return addr != NULL;
#endif // AMD64
}

////////////////////////////////////////////////////////////////////////////////
// thread stack

// Minimum usable stack sizes required to get to user code. Space for
// HotSpot guard pages is added later.
size_t os::Posix::_compiler_thread_min_stack_allowed = 48 * K;
size_t os::Posix::_java_thread_min_stack_allowed = 40 * K;
#ifdef _LP64
size_t os::Posix::_vm_internal_thread_min_stack_allowed = 64 * K;
#else
size_t os::Posix::_vm_internal_thread_min_stack_allowed = (48 DEBUG_ONLY(+ 4)) * K;
#endif // _LP64

// return default stack size for thr_type
size_t os::Posix::default_stack_size(os::ThreadType thr_type) {
  // default stack size (compiler thread needs larger stack)
#ifdef AMD64
  size_t s = (thr_type == os::compiler_thread ? 4 * M : 1 * M);
#else
  size_t s = (thr_type == os::compiler_thread ? 2 * M : 512 * K);
#endif // AMD64
  return s;
}

/////////////////////////////////////////////////////////////////////////////
// helper functions for fatal error handler

void os::print_context(outputStream *st, const void *context) {
  if (context == NULL) return;

  const ucontext_t *uc = (const ucontext_t*)context;
  st->print_cr("Registers:");
#ifdef AMD64
  st->print(  "RAX=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RAX]);
  st->print(", RBX=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RBX]);
  st->print(", RCX=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RCX]);
  st->print(", RDX=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RDX]);
  st->cr();
  st->print(  "RSP=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RSP]);
  st->print(", RBP=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RBP]);
  st->print(", RSI=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RSI]);
  st->print(", RDI=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RDI]);
  st->cr();
  st->print(  "R8 =" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R8]);
  st->print(", R9 =" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R9]);
  st->print(", R10=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R10]);
  st->print(", R11=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R11]);
  st->cr();
  st->print(  "R12=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R12]);
  st->print(", R13=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R13]);
  st->print(", R14=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R14]);
  st->print(", R15=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_R15]);
  st->cr();
  st->print(  "RIP=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_RIP]);
  st->print(", EFLAGS=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_EFL]);
  st->print(", CSGSFS=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_CSGSFS]);
  st->print(", ERR=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_ERR]);
  st->cr();
  st->print("  TRAPNO=" INTPTR_FORMAT, (intptr_t)uc->uc_mcontext.gregs[REG_TRAPNO]);
#else
  st->print(  "EAX=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EAX]);
  st->print(", EBX=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EBX]);
  st->print(", ECX=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_ECX]);
  st->print(", EDX=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EDX]);
  st->cr();
  st->print(  "ESP=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_UESP]);
  st->print(", EBP=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EBP]);
  st->print(", ESI=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_ESI]);
  st->print(", EDI=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EDI]);
  st->cr();
  st->print(  "EIP=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EIP]);
  st->print(", EFLAGS=" INTPTR_FORMAT, uc->uc_mcontext.gregs[REG_EFL]);
  st->print(", CR2=" PTR64_FORMAT, (uint64_t)uc->uc_mcontext.cr2);
#endif // AMD64
  st->cr();
  st->cr();

  intptr_t *sp = (intptr_t *)os::Linux::ucontext_get_sp(uc);
  st->print_cr("Top of Stack: (sp=" PTR_FORMAT ")", p2i(sp));
  print_hex_dump(st, (address)sp, (address)(sp + 8), sizeof(intptr_t));
  st->cr();

  // Note: it may be unsafe to inspect memory near pc. For example, pc may
  // point to garbage if entry point in an nmethod is corrupted. Leave
  // this at the end, and hope for the best.
  address pc = os::Linux::ucontext_get_pc(uc);
  print_instructions(st, pc, sizeof(char));
  st->cr();
}

void os::print_register_info(outputStream *st, const void *context) {
  if (context == NULL) return;

  const ucontext_t *uc = (const ucontext_t*)context;

  st->print_cr("Register to memory mapping:");
  st->cr();

  // this is horrendously verbose but the layout of the registers in the
  // context does not match how we defined our abstract Register set, so
  // we can't just iterate through the gregs area

  // this is only for the "general purpose" registers

#ifdef AMD64
  st->print("RAX="); print_location(st, uc->uc_mcontext.gregs[REG_RAX]);
  st->print("RBX="); print_location(st, uc->uc_mcontext.gregs[REG_RBX]);
  st->print("RCX="); print_location(st, uc->uc_mcontext.gregs[REG_RCX]);
  st->print("RDX="); print_location(st, uc->uc_mcontext.gregs[REG_RDX]);
  st->print("RSP="); print_location(st, uc->uc_mcontext.gregs[REG_RSP]);
  st->print("RBP="); print_location(st, uc->uc_mcontext.gregs[REG_RBP]);
  st->print("RSI="); print_location(st, uc->uc_mcontext.gregs[REG_RSI]);
  st->print("RDI="); print_location(st, uc->uc_mcontext.gregs[REG_RDI]);
  st->print("R8 ="); print_location(st, uc->uc_mcontext.gregs[REG_R8]);
  st->print("R9 ="); print_location(st, uc->uc_mcontext.gregs[REG_R9]);
  st->print("R10="); print_location(st, uc->uc_mcontext.gregs[REG_R10]);
  st->print("R11="); print_location(st, uc->uc_mcontext.gregs[REG_R11]);
  st->print("R12="); print_location(st, uc->uc_mcontext.gregs[REG_R12]);
  st->print("R13="); print_location(st, uc->uc_mcontext.gregs[REG_R13]);
  st->print("R14="); print_location(st, uc->uc_mcontext.gregs[REG_R14]);
  st->print("R15="); print_location(st, uc->uc_mcontext.gregs[REG_R15]);
#else
  st->print("EAX="); print_location(st, uc->uc_mcontext.gregs[REG_EAX]);
  st->print("EBX="); print_location(st, uc->uc_mcontext.gregs[REG_EBX]);
  st->print("ECX="); print_location(st, uc->uc_mcontext.gregs[REG_ECX]);
  st->print("EDX="); print_location(st, uc->uc_mcontext.gregs[REG_EDX]);
  st->print("ESP="); print_location(st, uc->uc_mcontext.gregs[REG_ESP]);
  st->print("EBP="); print_location(st, uc->uc_mcontext.gregs[REG_EBP]);
  st->print("ESI="); print_location(st, uc->uc_mcontext.gregs[REG_ESI]);
  st->print("EDI="); print_location(st, uc->uc_mcontext.gregs[REG_EDI]);
#endif // AMD64

  st->cr();
}

void os::setup_fpu() {
#ifndef AMD64
  address fpu_cntrl = StubRoutines::addr_fpu_cntrl_wrd_std();
  __asm__ volatile (  "fldcw (%0)" :
                      : "r" (fpu_cntrl) : "memory");
#endif // !AMD64
}

#ifndef PRODUCT
void os::verify_stack_alignment() {
#ifdef AMD64
  assert(((intptr_t)os::current_stack_pointer() & (StackAlignmentInBytes-1)) == 0, "incorrect stack alignment");
#endif
}
#endif


/*
 * IA32 only: execute code at a high address in case buggy NX emulation is present. I.e. avoid CS limit
 * updates (JDK-8023956).
 */
void os::workaround_expand_exec_shield_cs_limit() {
#if defined(IA32)
  assert(Linux::initial_thread_stack_bottom() != NULL, "sanity");
  size_t page_size = os::vm_page_size();

  /*
   * JDK-8197429
   *
   * Expand the stack mapping to the end of the initial stack before
   * attempting to install the codebuf.  This is needed because newer
   * Linux kernels impose a distance of a megabyte between stack
   * memory and other memory regions.  If we try to install the
   * codebuf before expanding the stack the installation will appear
   * to succeed but we'll get a segfault later if we expand the stack
   * in Java code.
   *
   */
  if (os::is_primordial_thread()) {
    address limit = Linux::initial_thread_stack_bottom();
    if (! DisablePrimordialThreadGuardPages) {
      limit += JavaThread::stack_red_zone_size() +
        JavaThread::stack_yellow_zone_size();
    }
    os::Linux::expand_stack_to(limit);
  }

  /*
   * Take the highest VA the OS will give us and exec
   *
   * Although using -(pagesz) as mmap hint works on newer kernel as you would
   * think, older variants affected by this work-around don't (search forward only).
   *
   * On the affected distributions, we understand the memory layout to be:
   *
   *   TASK_LIMIT= 3G, main stack base close to TASK_LIMT.
   *
   * A few pages south main stack will do it.
   *
   * If we are embedded in an app other than launcher (initial != main stack),
   * we don't have much control or understanding of the address space, just let it slide.
   */
  char* hint = (char*)(Linux::initial_thread_stack_bottom() -
                       (JavaThread::stack_guard_zone_size() + page_size));
  char* codebuf = os::attempt_reserve_memory_at(page_size, hint);

  if (codebuf == NULL) {
    // JDK-8197429: There may be a stack gap of one megabyte between
    // the limit of the stack and the nearest memory region: this is a
    // Linux kernel workaround for CVE-2017-1000364.  If we failed to
    // map our codebuf, try again at an address one megabyte lower.
    hint -= 1 * M;
    codebuf = os::attempt_reserve_memory_at(page_size, hint);
  }

  if ((codebuf == NULL) || (!os::commit_memory(codebuf, page_size, true))) {
    return; // No matter, we tried, best effort.
  }

  MemTracker::record_virtual_memory_type((address)codebuf, mtInternal);

  log_info(os)("[CS limit NX emulation work-around, exec code at: %p]", codebuf);

  // Some code to exec: the 'ret' instruction
  codebuf[0] = 0xC3;

  // Call the code in the codebuf
  __asm__ volatile("call *%0" : : "r"(codebuf));

  // keep the page mapped so CS limit isn't reduced.
#endif
}

int os::extra_bang_size_in_bytes() {
  // JDK-8050147 requires the full cache line bang for x86.
  return VM_Version::L1_line_size();
}
