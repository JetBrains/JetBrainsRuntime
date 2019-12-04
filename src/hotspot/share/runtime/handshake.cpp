/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/handshake.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/osThread.hpp"
#include "runtime/semaphore.inline.hpp"
#include "runtime/task.hpp"
#include "runtime/thread.hpp"
#include "runtime/vmThread.hpp"
#include "utilities/formatBuffer.hpp"
#include "utilities/preserveException.hpp"

class HandshakeOperation: public StackObj {
public:
  virtual void do_handshake(JavaThread* thread) = 0;
};

class HandshakeThreadsOperation: public HandshakeOperation {
  static Semaphore _done;
  HandshakeClosure* _handshake_cl;

public:
  HandshakeThreadsOperation(HandshakeClosure* cl) : _handshake_cl(cl) {}
  void do_handshake(JavaThread* thread);
  bool thread_has_completed() { return _done.trywait(); }
  const char* name() { return _handshake_cl->name(); }

#ifdef ASSERT
  void check_state() {
    assert(!_done.trywait(), "Must be zero");
  }
#endif
};

Semaphore HandshakeThreadsOperation::_done(0);

// Performing handshakes requires a custom yielding strategy because without it
// there is a clear performance regression vs plain spinning. We keep track of
// when we last saw progress by looking at why each targeted thread has not yet
// completed its handshake. After spinning for a while with no progress we will
// yield, but as long as there is progress, we keep spinning. Thus we avoid
// yielding when there is potential work to be done or the handshake is close
// to being finished.
class HandshakeSpinYield : public StackObj {
 private:
  jlong _start_time_ns;
  jlong _last_spin_start_ns;
  jlong _spin_time_ns;

  int _result_count[2][HandshakeState::_number_states];
  int _prev_result_pos;

  int prev_result_pos() { return _prev_result_pos & 0x1; }
  int current_result_pos() { return (_prev_result_pos + 1) & 0x1; }

  void wait_raw(jlong now) {
    // We start with fine-grained nanosleeping until a millisecond has
    // passed, at which point we resort to plain naked_short_sleep.
    if (now - _start_time_ns < NANOSECS_PER_MILLISEC) {
      os::naked_short_nanosleep(10 * (NANOUNITS / MICROUNITS));
    } else {
      os::naked_short_sleep(1);
    }
  }

  void wait_blocked(JavaThread* self, jlong now) {
    ThreadBlockInVM tbivm(self);
    wait_raw(now);
  }

  bool state_changed() {
    for (int i = 0; i < HandshakeState::_number_states; i++) {
      if (_result_count[0][i] != _result_count[1][i]) {
        return true;
      }
    }
    return false;
  }

  void reset_state() {
    _prev_result_pos++;
    for (int i = 0; i < HandshakeState::_number_states; i++) {
      _result_count[current_result_pos()][i] = 0;
    }
  }

 public:
  HandshakeSpinYield(jlong start_time) :
    _start_time_ns(start_time), _last_spin_start_ns(start_time),
    _spin_time_ns(0), _result_count(), _prev_result_pos(0) {

    const jlong max_spin_time_ns = 100 /* us */ * (NANOUNITS / MICROUNITS);
    int free_cpus = os::active_processor_count() - 1;
    _spin_time_ns = (5 /* us */ * (NANOUNITS / MICROUNITS)) * free_cpus; // zero on UP
    _spin_time_ns = _spin_time_ns > max_spin_time_ns ? max_spin_time_ns : _spin_time_ns;
  }

  void add_result(HandshakeState::ProcessResult pr) {
    _result_count[current_result_pos()][pr]++;
  }

  void process() {
    jlong now = os::javaTimeNanos();
    if (state_changed()) {
      reset_state();
      // We spin for x amount of time since last state change.
      _last_spin_start_ns = now;
      return;
    }
    jlong wait_target = _last_spin_start_ns + _spin_time_ns;
    if (wait_target < now) {
      // On UP this is always true.
      Thread* self = Thread::current();
      if (self->is_Java_thread()) {
        wait_blocked((JavaThread*)self, now);
      } else {
        wait_raw(now);
      }
      _last_spin_start_ns = os::javaTimeNanos();
    }
    reset_state();
  }
};

class VM_Handshake: public VM_Operation {
  const jlong _handshake_timeout;
 public:
  bool evaluate_at_safepoint() const { return false; }

  bool evaluate_concurrently() const { return false; }

 protected:
  HandshakeThreadsOperation* const _op;

  VM_Handshake(HandshakeThreadsOperation* op) :
      _op(op),
      _handshake_timeout(TimeHelper::millis_to_counter(HandshakeTimeout)) {}

  void set_handshake(JavaThread* target) {
    target->set_handshake_operation(_op);
  }

  // This method returns true for threads completed their operation
  // and true for threads canceled their operation.
  // A cancellation can happen if the thread is exiting.
  bool poll_for_completed_thread() { return _op->thread_has_completed(); }

  bool handshake_has_timed_out(jlong start_time);
  static void handle_timeout();
};

bool VM_Handshake::handshake_has_timed_out(jlong start_time) {
  // Check if handshake operation has timed out
  if (_handshake_timeout > 0) {
    return os::javaTimeNanos() >= (start_time + _handshake_timeout);
  }
  return false;
}

void VM_Handshake::handle_timeout() {
  LogStreamHandle(Warning, handshake) log_stream;
  for (JavaThreadIteratorWithHandle jtiwh; JavaThread *thr = jtiwh.next(); ) {
    if (thr->has_handshake()) {
      log_stream.print("Thread " PTR_FORMAT " has not cleared its handshake op", p2i(thr));
      thr->print_thread_state_on(&log_stream);
    }
  }
  log_stream.flush();
  fatal("Handshake operation timed out");
}

static void log_handshake_info(jlong start_time_ns, const char* name, int targets, int vmt_executed, const char* extra = NULL) {
  if (start_time_ns != 0) {
    jlong completion_time = os::javaTimeNanos() - start_time_ns;
    log_info(handshake)("Handshake \"%s\", Targeted threads: %d, Executed by targeted threads: %d, Total completion time: " JLONG_FORMAT " ns%s%s",
                        name, targets,
                        targets - vmt_executed,
                        completion_time,
                        extra != NULL ? ", " : "",
                        extra != NULL ? extra : "");
  }
}

class VM_HandshakeOneThread: public VM_Handshake {
  JavaThread* _target;
  bool _thread_alive;
 public:
  VM_HandshakeOneThread(HandshakeThreadsOperation* op, JavaThread* target) :
    VM_Handshake(op), _target(target), _thread_alive(false) {}

  void doit() {
    DEBUG_ONLY(_op->check_state();)
    jlong start_time_ns = os::javaTimeNanos();

    ThreadsListHandle tlh;
    if (tlh.includes(_target)) {
      set_handshake(_target);
      _thread_alive = true;
    } else {
      log_handshake_info(start_time_ns, _op->name(), 0, 0, "(thread dead)");
      return;
    }

    if (!UseMembar) {
      os::serialize_thread_states();
    }

    log_trace(handshake)("Thread signaled, begin processing by VMThtread");
    HandshakeState::ProcessResult pr = HandshakeState::_no_operation;
    HandshakeSpinYield hsy(start_time_ns);
    do {
      if (handshake_has_timed_out(start_time_ns)) {
        handle_timeout();
      }

      // We need to re-think this with SMR ThreadsList.
      // There is an assumption in the code that the Threads_lock should be
      // locked during certain phases.
      {
        MutexLockerEx ml(Threads_lock, Mutex::_no_safepoint_check_flag);
        pr = _target->handshake_try_process_by_vmThread(_op);
      }
      hsy.add_result(pr);
      hsy.process();
    } while (!poll_for_completed_thread());
    DEBUG_ONLY(_op->check_state();)
    log_handshake_info(start_time_ns, _op->name(), 1, (pr == HandshakeState::_success) ? 1 : 0);
  }

  VMOp_Type type() const { return VMOp_HandshakeOneThread; }

  bool thread_alive() const { return _thread_alive; }
};

class VM_HandshakeAllThreads: public VM_Handshake {
 public:
  VM_HandshakeAllThreads(HandshakeThreadsOperation* op) : VM_Handshake(op) {}

  void doit() {
    DEBUG_ONLY(_op->check_state();)

    jlong start_time_ns = os::javaTimeNanos();
    int handshake_executed_by_vm_thread = 0;

    JavaThreadIteratorWithHandle jtiwh;
    int number_of_threads_issued = 0;
    for (JavaThread *thr = jtiwh.next(); thr != NULL; thr = jtiwh.next()) {
      set_handshake(thr);
      number_of_threads_issued++;
    }

    if (number_of_threads_issued < 1) {
      log_handshake_info(start_time_ns, _op->name(), 0, 0);
      return;
    }

    if (!UseMembar) {
      os::serialize_thread_states();
    }

    log_trace(handshake)("Threads signaled, begin processing blocked threads by VMThread");
    HandshakeSpinYield hsy(start_time_ns);
    int number_of_threads_completed = 0;
    do {
      // Check if handshake operation has timed out
      if (handshake_has_timed_out(start_time_ns)) {
        handle_timeout();
      }

      // Have VM thread perform the handshake operation for blocked threads.
      // Observing a blocked state may of course be transient but the processing is guarded
      // by semaphores and we optimistically begin by working on the blocked threads
      {
          // We need to re-think this with SMR ThreadsList.
          // There is an assumption in the code that the Threads_lock should
          // be locked during certain phases.
          jtiwh.rewind();
          MutexLockerEx ml(Threads_lock, Mutex::_no_safepoint_check_flag);
          for (JavaThread *thr = jtiwh.next(); thr != NULL; thr = jtiwh.next()) {
            // A new thread on the ThreadsList will not have an operation,
            // hence it is skipped in handshake_try_process_by_vmthread.
            HandshakeState::ProcessResult pr = thr->handshake_try_process_by_vmThread(_op);
            if (pr == HandshakeState::_success) {
              handshake_executed_by_vm_thread++;
            }
            hsy.add_result(pr);
          }
          hsy.process();
      }

      while (poll_for_completed_thread()) {
        // Includes canceled operations by exiting threads.
        number_of_threads_completed++;
      }

    } while (number_of_threads_issued > number_of_threads_completed);
    assert(number_of_threads_issued == number_of_threads_completed, "Must be the same");
    DEBUG_ONLY(_op->check_state();)

    log_handshake_info(start_time_ns, _op->name(), number_of_threads_issued, handshake_executed_by_vm_thread);
  }

  VMOp_Type type() const { return VMOp_HandshakeAllThreads; }
};

class VM_HandshakeFallbackOperation : public VM_Operation {
  HandshakeClosure* _handshake_cl;
  Thread* _target_thread;
  bool _all_threads;
  bool _thread_alive;
public:
  VM_HandshakeFallbackOperation(HandshakeClosure* cl) :
      _handshake_cl(cl), _target_thread(NULL), _all_threads(true) {}
  VM_HandshakeFallbackOperation(HandshakeClosure* cl, Thread* target) :
      _handshake_cl(cl), _target_thread(target), _all_threads(false) {}

  void doit() {
    log_trace(handshake)("VMThread executing VM_HandshakeFallbackOperation, operation: %s", name());
    for (JavaThreadIteratorWithHandle jtiwh; JavaThread *t = jtiwh.next(); ) {
      if (_all_threads || t == _target_thread) {
        if (t == _target_thread) {
          _thread_alive = true;
        }
        _handshake_cl->do_thread(t);
      }
    }
  }

  VMOp_Type type() const { return VMOp_HandshakeFallback; }
  bool thread_alive() const { return _thread_alive; }
};

void HandshakeThreadsOperation::do_handshake(JavaThread* thread) {
  jlong start_time_ns = 0;
  if (log_is_enabled(Debug, handshake, task)) {
    start_time_ns = os::javaTimeNanos();
  }

  // Only actually execute the operation for non terminated threads.
  if (!thread->is_terminated()) {
    _handshake_cl->do_thread(thread);
  }

  // Use the semaphore to inform the VM thread that we have completed the operation
  _done.signal();

  if (start_time_ns != 0) {
    jlong completion_time = os::javaTimeNanos() - start_time_ns;
    log_debug(handshake, task)("Operation: %s for thread " PTR_FORMAT ", is_vm_thread: %s, completed in " JLONG_FORMAT " ns",
                               name(), p2i(thread), BOOL_TO_STR(Thread::current()->is_VM_thread()), completion_time);
  }
}

void Handshake::execute(HandshakeClosure* thread_cl) {
  if (ThreadLocalHandshakes) {
    HandshakeThreadsOperation cto(thread_cl);
    VM_HandshakeAllThreads handshake(&cto);
    VMThread::execute(&handshake);
  } else {
    VM_HandshakeFallbackOperation op(thread_cl);
    VMThread::execute(&op);
  }
}

bool Handshake::execute(HandshakeClosure* thread_cl, JavaThread* target) {
  if (ThreadLocalHandshakes) {
    HandshakeThreadsOperation cto(thread_cl);
    VM_HandshakeOneThread handshake(&cto, target);
    VMThread::execute(&handshake);
    return handshake.thread_alive();
  } else {
    VM_HandshakeFallbackOperation op(thread_cl, target);
    VMThread::execute(&op);
    return op.thread_alive();
  }
}

HandshakeState::HandshakeState() : _operation(NULL), _semaphore(1), _thread_in_process_handshake(false) {}

void HandshakeState::set_operation(JavaThread* target, HandshakeOperation* op) {
  _operation = op;
  SafepointMechanism::arm_local_poll_release(target);
}

void HandshakeState::clear_handshake(JavaThread* target) {
  _operation = NULL;
  SafepointMechanism::disarm_local_poll_release(target);
}

void HandshakeState::process_self_inner(JavaThread* thread) {
  assert(Thread::current() == thread, "should call from thread");
  assert(!thread->is_terminated(), "should not be a terminated thread");

  ThreadInVMForHandshake tivm(thread);
  if (!_semaphore.trywait()) {
    _semaphore.wait_with_safepoint_check(thread);
  }
  HandshakeOperation* op = OrderAccess::load_acquire(&_operation);
  if (op != NULL) {
    HandleMark hm(thread);
    CautiouslyPreserveExceptionMark pem(thread);
    // Disarm before execute the operation
    clear_handshake(thread);
    op->do_handshake(thread);
  }
  _semaphore.signal();
}

bool HandshakeState::vmthread_can_process_handshake(JavaThread* target) {
  // SafepointSynchronize::safepoint_safe() does not consider an externally
  // suspended thread to be safe. However, this function must be called with
  // the Threads_lock held so an externally suspended thread cannot be
  // resumed thus it is safe.
  assert(Threads_lock->owned_by_self(), "Not holding Threads_lock.");
  return SafepointSynchronize::safepoint_safe(target, target->thread_state()) ||
         target->is_ext_suspended() || target->is_terminated();
}

static bool possibly_vmthread_can_process_handshake(JavaThread* target) {
  // An externally suspended thread cannot be resumed while the
  // Threads_lock is held so it is safe.
  // Note that this method is allowed to produce false positives.
  assert(Threads_lock->owned_by_self(), "Not holding Threads_lock.");
  if (target->is_ext_suspended()) {
    return true;
  }
  if (target->is_terminated()) {
    return true;
  }
  switch (target->thread_state()) {
  case _thread_in_native:
    // native threads are safe if they have no java stack or have walkable stack
    return !target->has_last_Java_frame() || target->frame_anchor()->walkable();

  case _thread_blocked:
    return true;

  default:
    return false;
  }
}

bool HandshakeState::claim_handshake_for_vmthread() {
  if (!_semaphore.trywait()) {
    return false;
  }
  if (has_operation()) {
    return true;
  }
  _semaphore.signal();
  return false;
}

HandshakeState::ProcessResult HandshakeState::try_process_by_vmThread(JavaThread* target) {
  assert(Thread::current()->is_VM_thread(), "should call from vm thread");
  // Threads_lock must be held here, but that is assert()ed in
  // possibly_vmthread_can_process_handshake().

  if (!has_operation()) {
    // JT has already cleared its handshake
    return _no_operation;
  }

  if (!possibly_vmthread_can_process_handshake(target)) {
    // JT is observed in an unsafe state, it must notice the handshake itself
    return _not_safe;
  }

  // Claim the semaphore if there still an operation to be executed.
  if (!claim_handshake_for_vmthread()) {
    return _state_busy;
  }

  // If we own the semaphore at this point and while owning the semaphore
  // can observe a safe state the thread cannot possibly continue without
  // getting caught by the semaphore.
  ProcessResult pr = _not_safe;
  if (vmthread_can_process_handshake(target)) {
    guarantee(!_semaphore.trywait(), "we should already own the semaphore");
    _operation->do_handshake(target);
    // Disarm after VM thread have executed the operation.
    clear_handshake(target);
    // Release the thread
    pr = _success;
  }

  _semaphore.signal();
  return pr;
}
