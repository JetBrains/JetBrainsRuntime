/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/types.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <sys/types.h>
#include <unistd.h>

// TODO sort includes
// TODO add casts

typedef struct {
  __u16 type;
  __u16 code;
  __s32 value;
} TEventData;

static int set_bit(int fd, unsigned long int request, unsigned long int bit) {
  return ioctl(fd, request, bit);
}

static int touch_begin(int fd, int tracking_id, int x, int y) {
  struct input_event ev;
  int i = 0;
  const int cnt = 7;
  TEventData eventData[7] = {{EV_ABS, ABS_MT_TRACKING_ID, tracking_id},
                             {EV_ABS, ABS_MT_POSITION_X, x},
                             {EV_ABS, ABS_MT_POSITION_Y, y},
                             {EV_KEY, BTN_TOUCH, 1},
                             {EV_ABS, ABS_X, x},
                             {EV_ABS, ABS_Y, y},
                             {EV_SYN, 0, 0}};

  for (i = 0; i < cnt; i++) {
    memset(&ev, 0, sizeof(struct input_event));
    ev.type = eventData[i].type;
    ev.code = eventData[i].code;
    ev.value = eventData[i].value;

    if (write(fd, &ev, sizeof(struct input_event)) < 0) {
      return -1;
    }
  }
  return 0;
}

static int touch_update(int fd, int x, int y) {
  struct input_event ev;
  int i = 0;
  const int cnt = 5;
  TEventData eventData[5] = {{EV_ABS, ABS_MT_POSITION_X, x},
                             {EV_ABS, ABS_MT_POSITION_Y, y},
                             {EV_ABS, ABS_X, x},
                             {EV_ABS, ABS_Y, y},
                             {EV_SYN, 0, 0}};

  for (i = 0; i < cnt; i++) {
    memset(&ev, 0, sizeof(struct input_event));
    ev.type = eventData[i].type;
    ev.code = eventData[i].code;
    ev.value = eventData[i].value;

    if (write(fd, &ev, sizeof(struct input_event)) < 0) {
      return -1;
    }
  }
  return 0;
}

// TODO consider different name in case of multitouch
static int touch_end(int fd) {
  struct input_event ev;
  int i = 0;
  const int cnt = 3;
  TEventData eventData[3] = {
      {EV_ABS, ABS_MT_TRACKING_ID, -1}, {EV_KEY, BTN_TOUCH, 0}, {EV_SYN, 0, 0}};

  for (i = 0; i < cnt; i++) {
    memset(&ev, 0, sizeof(struct input_event));
    ev.type = eventData[i].type;
    ev.code = eventData[i].code;
    ev.value = eventData[i].value;

    if (write(fd, &ev, sizeof(struct input_event)) < 0) {
      return -1;
    }
  }
  return 0;
}

/*
 * Class:     TouchScreenDevice
 * Method:    create
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_LinuxTouchScreenDevice_create(JNIEnv *env,
                                                                  jobject o,
                                                                  jint width,
                                                                  jint height) {
  int productId = 123;
  const int FAKE_VENDOR_ID = 0x453;
  const int MAX_FINGER_COUNT = 9;
  const int MAX_TRACKING_ID = 65535;

  int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
  if (fd < 0) {
    return -1;
  }

  int ret_code = 0;
  ret_code = set_bit(fd, UI_SET_EVBIT, EV_SYN);
  ret_code = set_bit(fd, UI_SET_EVBIT, EV_KEY);
  ret_code = set_bit(fd, UI_SET_KEYBIT, BTN_TOUCH);
  ret_code = set_bit(fd, UI_SET_EVBIT, EV_ABS);
  ret_code = set_bit(fd, UI_SET_ABSBIT, ABS_X);
  ret_code = set_bit(fd, UI_SET_ABSBIT, ABS_Y);
  ret_code = set_bit(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
  ret_code = set_bit(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
  ret_code = set_bit(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
  ret_code = set_bit(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
  ret_code = set_bit(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

  if (ret_code < 0) {
    return -1;
  }

  struct uinput_user_dev virtual_touch_device;
  memset(&virtual_touch_device, 0, sizeof(virtual_touch_device));
  snprintf(virtual_touch_device.name, UINPUT_MAX_NAME_SIZE,
           "Virtual Touch Device - %#x", productId);
  virtual_touch_device.id.bustype = BUS_VIRTUAL;
  virtual_touch_device.id.vendor = FAKE_VENDOR_ID;
  virtual_touch_device.id.product = productId;
  virtual_touch_device.id.version = 1;

  virtual_touch_device.absmin[ABS_X] = 0;
  virtual_touch_device.absmax[ABS_X] = width;
  virtual_touch_device.absmin[ABS_Y] = 0;
  virtual_touch_device.absmax[ABS_Y] = height;
  virtual_touch_device.absmin[ABS_MT_SLOT] = 0;
  virtual_touch_device.absmax[ABS_MT_SLOT] = MAX_FINGER_COUNT;
  virtual_touch_device.absmin[ABS_MT_POSITION_X] = 0;
  virtual_touch_device.absmax[ABS_MT_POSITION_X] = width;
  virtual_touch_device.absmin[ABS_MT_POSITION_Y] = 0;
  virtual_touch_device.absmax[ABS_MT_POSITION_Y] = height;
  virtual_touch_device.absmin[ABS_MT_TRACKING_ID] = 0;
  virtual_touch_device.absmax[ABS_MT_TRACKING_ID] = MAX_TRACKING_ID;

  ret_code = write(fd, &virtual_touch_device, sizeof(virtual_touch_device));
  ret_code = ioctl(fd, UI_DEV_CREATE);
  if (ret_code < 0) {
    return -1;
  }

  return fd;
}

/*
 * Class:     TouchScreenDevice
 * Method:    destroy
 * Signature: (I)V
 */
JNIEXPORT jint JNICALL Java_LinuxTouchScreenDevice_destroy(JNIEnv *env,
                                                                   jobject o,
                                                                   jint fd) {
  if (ioctl(fd, UI_DEV_DESTROY) < 0) {
    return -1;
  }

  return close(fd);
}

/*
 * Class:     TouchScreenDevice
 * Method:    clickImpl
 * Signature: (IIII)V
 */
// TODO return code with checked exception
JNIEXPORT jint JNICALL Java_LinuxTouchScreenDevice_clickImpl(
    JNIEnv *env, jobject o, jint fd, jint trackingId, jint x, jint y) {
  touch_begin(fd, trackingId, x, y);
  touch_end(fd);
  return 0;
}

/*
 * Class:     TouchScreenDevice
 * Method:    moveImpl
 * Signature: (IIIIII)V
 */
JNIEXPORT jint JNICALL Java_LinuxTouchScreenDevice_moveImpl(
    JNIEnv *env, jobject o, jint fd, jint trackingId, jint fromX, jint fromY,
    jint toX, jint toY) {
  touch_begin(fd, trackingId, fromX, fromY);
  touch_update(fd, toX, toY);
  touch_end(fd);
  return 0;
}
