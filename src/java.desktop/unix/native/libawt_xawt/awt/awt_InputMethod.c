/*
 * Copyright (c) 1997, 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

#ifdef HEADLESS
    #error This file should not be included in headless library
#endif

#include "awt.h"
#include "awt_p.h"
#include "debug_assert.h"

#include <sun_awt_X11InputMethodBase.h>
#include <sun_awt_X11_XInputMethod.h>
#include <sun_awt_X11_XInputMethod_BrokenImDetectionContext.h>

#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <X11/keysym.h>
#include <X11/Xlib.h>

#define THROW_OUT_OF_MEMORY_ERROR() \
        JNU_ThrowOutOfMemoryError((JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2), NULL)

struct X11InputMethodIDs {
  jfieldID pData;
} x11InputMethodIDs;

static int PreeditStartCallback(XIC, XPointer, XPointer);
static void PreeditDoneCallback(XIC, XPointer, XPointer);
static void PreeditDrawCallback(XIC, XPointer,
                                XIMPreeditDrawCallbackStruct *);
static void PreeditCaretCallback(XIC, XPointer,
                                 XIMPreeditCaretCallbackStruct *);
#if defined(__linux__)
static void StatusStartCallback(XIC, XPointer, XPointer);
static void StatusDoneCallback(XIC, XPointer, XPointer);
static void StatusDrawCallback(XIC, XPointer,
                               XIMStatusDrawCallbackStruct *);
#endif

#define ROOT_WINDOW_STYLES      (XIMPreeditNothing | XIMStatusNothing)
#define NO_STYLES               (XIMPreeditNone | XIMStatusNone)

#define PreeditStartIndex       0
#define PreeditDoneIndex        1
#define PreeditDrawIndex        2
#define PreeditCaretIndex       3
#if defined(__linux__)
#define StatusStartIndex        4
#define StatusDoneIndex         5
#define StatusDrawIndex         6
#define NCALLBACKS              7
#else
#define NCALLBACKS              4
#endif

/*
 * Callback function pointers: the order has to match the *Index
 * values above.
 */
static XIMProc callback_funcs[NCALLBACKS] = {
    (XIMProc)(void *)&PreeditStartCallback,
    (XIMProc)PreeditDoneCallback,
    (XIMProc)PreeditDrawCallback,
    (XIMProc)PreeditCaretCallback,
#if defined(__linux__)
    (XIMProc)StatusStartCallback,
    (XIMProc)StatusDoneCallback,
    (XIMProc)StatusDrawCallback,
#endif
};

#if defined(__linux__)
#define MAX_STATUS_LEN  100
typedef struct {
    Window   w;                /*status window id        */
    Window   root;             /*the root window id      */
    Window   parent;           /*parent shell window     */
    int      x, y;             /*parent's upperleft position */
    int      width, height;    /*parent's width, height  */
    GC       lightGC;          /*gc for light border     */
    GC       dimGC;            /*gc for dim border       */
    GC       bgGC;             /*normal painting         */
    GC       fgGC;             /*normal painting         */
    int      statusW, statusH; /*status window's w, h    */
    int      rootW, rootH;     /*root window's w, h    */
    int      bWidth;           /*border width            */
    char     status[MAX_STATUS_LEN]; /*status text       */
    XFontSet fontset;           /*fontset for drawing    */
    int      off_x, off_y;
    Bool     on;                /*if the status window on*/
} StatusWindow;
#endif


// ===================================================== JBR-2460 =====================================================

/**
 * The structure keeps an instance of XIC and some other dynamic resources attached to the XIC which have to be fred
 * when the XIC is destroyed
 *
 * @see jbNewXimClient_createInputContextOfStyle
 */
typedef struct jbNewXimClient_ExtendedInputContext {
    XIC xic;

    /**
     * The input style (XNInputStyle) used to create the XIC.
     */
    XIMStyle inputStyle;

    /**
     * The display of XIM used to create the XIC and the fontsets.
     * Mustn't be NULL if xic isn't NULL.
     */
    Display* xicDisplay;

    /**
     * NULL if the XNFontSet attribute of XNPreeditAttributes of xic hasn't been specified manually.
     * Otherwise it has to be freed via XFreeFontSet when xic is destroyed AND the font set is no longer needed.
     * The pointer can be equal to statusCustomFontSet, so don't forget to handle such a case before calling XFreeFontSet.
     */
    XFontSet preeditCustomFontSet;

    /**
     * NULL if the XNFontSet attribute of XNStatusAttributes of  xic hasn't been specified manually.
     * Otherwise it has to be freed via XFreeFontSet when xic is destroyed AND the font set is no longer needed.
     * The pointer can be equal to preeditCustomFontSet, so don't forget to handle such a case before calling XFreeFontSet.
     */
    XFontSet statusCustomFontSet;

    /**
     * NULL if the input style of xic contains neither XIMPreeditCallbacks nor XIMStatusCallbacks.
     * Otherwise the array consist of values for the following properties and has to be freed:
     *   - XNPreeditStartCallback
     *   - XNPreeditDoneCallback
     *   - XNPreeditDrawCallback
     *   - XNPreeditCaretCallback
     *   - XNStatusStartCallback
     *   - XNStatusDoneCallback
     *   - XNStatusDrawCallback
     */
    XIMCallback (*preeditAndStatusCallbacks)[NCALLBACKS];
} jbNewXimClient_ExtendedInputContext;

/**
 * Just sets all fields of the context to the specified values.
 */
static inline void jbNewXimClient_setInputContextFields(
    // Non-nullable
    jbNewXimClient_ExtendedInputContext *context,
    // Nullable
    XIC xic,
    XIMStyle inputStyle,
    // Nullable
    Display *xicDisplay,
    // Nullable
    XFontSet preeditCustomFontSet,
    // Nullable
    XFontSet statusCustomFontSet,
    // Nullable
    XIMCallback (*preeditAndStatusCallbacks)[NCALLBACKS]
);

/**
 * Destroys the input context previously created by jbNewXimClient_createInputContextOfStyle.
 * @param[in,out] context - a pointer to the context which is going to be destroyed.
 */
static void jbNewXimClient_destroyInputContext(jbNewXimClient_ExtendedInputContext *context);

// ====================================================================================================================


/*
 * X11InputMethodData keeps per X11InputMethod instance information. A pointer
 * to this data structure is kept in an X11InputMethod object (pData).
 */
typedef struct _X11InputMethodData {
    XIC                                 current_ic;     /* current X Input Context */
    jbNewXimClient_ExtendedInputContext ic_active;      /* X Input Context for active clients */
    jbNewXimClient_ExtendedInputContext ic_passive;     /* X Input Context for passive clients */
    jobject                             x11inputmethod; /* global ref to X11InputMethod instance */
                                                        /* associated with the XIC */
#if defined(__linux__)
    StatusWindow                        *statusWindow;  /* our own status window  */
#endif
    char                                *lookup_buf;    /* buffer used for XmbLookupString */
    int                                 lookup_buf_len; /* lookup buffer size in bytes */

    struct {
        Boolean isBetweenPreeditStartAndPreeditDone;
    }                                   brokenImDetectionContext;
} X11InputMethodData;

/*
 * When XIC is created, a global reference is created for
 * sun.awt.X11InputMethod object so that it could be used by the XIM callback
 * functions. This could be a dangerous thing to do when the original
 * X11InputMethod object is garbage collected and as a result,
 * destroyX11InputMethodData is called to delete the global reference.
 * If any XIM callback function still holds and uses the "already deleted"
 * global reference, disaster is going to happen. So we have to maintain
 * a list for these global references which is consulted first when the
 * callback functions or any function tries to use "currentX11InputMethodObject"
 * which always refers to the global reference try to use it.
 *
 */
typedef struct _X11InputMethodGRefNode {
    jobject inputMethodGRef;
    struct _X11InputMethodGRefNode* next;
} X11InputMethodGRefNode;

X11InputMethodGRefNode *x11InputMethodGRefListHead = NULL;

/* reference to the current X11InputMethod instance, it is always
   point to the global reference to the X11InputMethodObject since
   it could be referenced by different threads. */
jobject currentX11InputMethodInstance = NULL;

Window  currentFocusWindow = 0;  /* current window that has focus for input
                                       method. (the best place to put this
                                       information should be
                                       currentX11InputMethodInstance's pData) */
static XIM X11im = NULL;
Display * dpy = NULL;

#define GetJNIEnv() (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2)

static void DestroyXIMCallback(XIM, XPointer, XPointer);
static void OpenXIMCallback(Display *, XPointer, XPointer);
/* Solaris XIM Extension */
#define XNCommitStringCallback "commitStringCallback"
static void CommitStringCallback(XIC, XPointer, XPointer);

static X11InputMethodData * getX11InputMethodData(JNIEnv *, jobject);
static void setX11InputMethodData(JNIEnv *, jobject, X11InputMethodData *);
static void destroyX11InputMethodData(JNIEnv *, X11InputMethodData *);
static void freeX11InputMethodData(JNIEnv *, X11InputMethodData *);
#if defined(__linux__)
static Window getParentWindow(Window);
#endif

/*
 * This function is stolen from /src/solaris/hpi/src/system_md.c
 * It is used in setting the time in Java-level InputEvents
 */
jlong
awt_util_nowMillisUTC()
{
    struct timeval t;
    gettimeofday(&t, NULL);
    return ((jlong)t.tv_sec) * 1000 + (jlong)(t.tv_usec/1000);
}

/*
 * Converts the wchar_t string to a multi-byte string calling wcstombs(). A
 * buffer is allocated by malloc() to store the multi-byte string. NULL is
 * returned if the given wchar_t string pointer is NULL or buffer allocation is
 * failed.
 */
static char *
wcstombsdmp(wchar_t *wcs, int len)
{
    size_t n;
    char *mbs;

    if (wcs == NULL)
        return NULL;

    n = len*MB_CUR_MAX + 1;

    mbs = (char *) malloc(n * sizeof(char));
    if (mbs == NULL) {
        THROW_OUT_OF_MEMORY_ERROR();
        return NULL;
    }

    /* TODO: check return values... Handle invalid characters properly...  */
    if (wcstombs(mbs, wcs, n) == (size_t)-1) {
        free(mbs);
        return NULL;
    }

    return mbs;
}

/*
 * Returns True if the global reference is still in the list,
 * otherwise False.
 */
static Bool isX11InputMethodGRefInList(jobject imGRef) {
    X11InputMethodGRefNode *pX11InputMethodGRef = x11InputMethodGRefListHead;

    if (imGRef == NULL) {
        return False;
    }

    while (pX11InputMethodGRef != NULL) {
        if (pX11InputMethodGRef->inputMethodGRef == imGRef) {
            return True;
        }
        pX11InputMethodGRef = pX11InputMethodGRef->next;
    }

    return False;
}

/*
 * Add the new created global reference to the list.
 */
static void addToX11InputMethodGRefList(jobject newX11InputMethodGRef) {
    X11InputMethodGRefNode *newNode = NULL;

    if (newX11InputMethodGRef == NULL ||
        isX11InputMethodGRefInList(newX11InputMethodGRef)) {
        return;
    }

    newNode = (X11InputMethodGRefNode *)malloc(sizeof(X11InputMethodGRefNode));

    if (newNode == NULL) {
        return;
    } else {
        newNode->inputMethodGRef = newX11InputMethodGRef;
        newNode->next = x11InputMethodGRefListHead;
        x11InputMethodGRefListHead = newNode;
    }
}

/*
 * Remove the global reference from the list.
 */
static void removeX11InputMethodGRefFromList(jobject x11InputMethodGRef) {
     X11InputMethodGRefNode *pX11InputMethodGRef = NULL;
     X11InputMethodGRefNode *cX11InputMethodGRef = x11InputMethodGRefListHead;

     if (x11InputMethodGRefListHead == NULL ||
         x11InputMethodGRef == NULL) {
         return;
     }

     /* cX11InputMethodGRef always refers to the current node while
        pX11InputMethodGRef refers to the previous node.
     */
     while (cX11InputMethodGRef != NULL) {
         if (cX11InputMethodGRef->inputMethodGRef == x11InputMethodGRef) {
             break;
         }
         pX11InputMethodGRef = cX11InputMethodGRef;
         cX11InputMethodGRef = cX11InputMethodGRef->next;
     }

     if (cX11InputMethodGRef == NULL) {
         return; /* Not found. */
     }

     if (cX11InputMethodGRef == x11InputMethodGRefListHead) {
         x11InputMethodGRefListHead = x11InputMethodGRefListHead->next;
     } else {
         pX11InputMethodGRef->next = cX11InputMethodGRef->next;
     }
     free(cX11InputMethodGRef);

     return;
}


static X11InputMethodData * getX11InputMethodData(JNIEnv * env, jobject imInstance) {
    X11InputMethodData *pX11IMData =
        (X11InputMethodData *)JNU_GetLongFieldAsPtr(env, imInstance, x11InputMethodIDs.pData);

    /*
     * In case the XIM server was killed somehow, reset X11InputMethodData.
     */
    if (X11im == NULL && pX11IMData != NULL) {
        JNU_CallMethodByName(env, NULL, pX11IMData->x11inputmethod,
                             "flushText",
                             "()V");
        JNU_CHECK_EXCEPTION_RETURN(env, NULL);
        /* IMPORTANT:
           The order of the following calls is critical since "imInstance" may
           point to the global reference itself, if "freeX11InputMethodData" is called
           first, the global reference will be destroyed and "setX11InputMethodData"
           will in fact fail silently. So pX11IMData will not be set to NULL.
           This could make the original java object refers to a deleted pX11IMData
           object.
        */
        setX11InputMethodData(env, imInstance, NULL);
        freeX11InputMethodData(env, pX11IMData);
        pX11IMData = NULL;
    }

    return pX11IMData;
}

static void setX11InputMethodData(JNIEnv * env, jobject imInstance, X11InputMethodData *pX11IMData) {
    JNU_SetLongFieldFromPtr(env, imInstance, x11InputMethodIDs.pData, pX11IMData);
}

static void
destroyXInputContexts(X11InputMethodData *pX11IMData) {
    if (pX11IMData == NULL) {
        return;
    }

    if (pX11IMData->ic_active.xic != (XIC)0) {
        if (pX11IMData->ic_passive.xic == pX11IMData->ic_active.xic) {
            // To avoid double-free
            jbNewXimClient_setInputContextFields(&pX11IMData->ic_passive, NULL, 0, NULL, NULL, NULL, NULL);
        }

        XUnsetICFocus(pX11IMData->ic_active.xic);
        jbNewXimClient_destroyInputContext(&pX11IMData->ic_active);
    }

    if (pX11IMData->ic_passive.xic != (XIC)0) {
        XUnsetICFocus(pX11IMData->ic_passive.xic);
        jbNewXimClient_destroyInputContext(&pX11IMData->ic_passive);
    }

    pX11IMData->current_ic = (XIC)0;
}

/* this function should be called within AWT_LOCK() */
static void
destroyX11InputMethodData(JNIEnv *env, X11InputMethodData *pX11IMData)
{
    /*
     * Destroy XICs
     */
    if (pX11IMData == NULL) {
        return;
    }

    destroyXInputContexts(pX11IMData);
    freeX11InputMethodData(env, pX11IMData);
}

static void
freeX11InputMethodData(JNIEnv *env, X11InputMethodData *pX11IMData)
{
#if defined(__linux__)
    if (pX11IMData->statusWindow != NULL){
        StatusWindow *sw = pX11IMData->statusWindow;
        XFreeGC(awt_display, sw->lightGC);
        XFreeGC(awt_display, sw->dimGC);
        XFreeGC(awt_display, sw->bgGC);
        XFreeGC(awt_display, sw->fgGC);
        if (sw->fontset != NULL) {
            XFreeFontSet(awt_display, sw->fontset);
        }
        XDestroyWindow(awt_display, sw->w);
        free((void*)sw);
    }
#endif

    if (env) {
        /* Remove the global reference from the list, so that
           the callback function or whoever refers to it could know.
        */
        removeX11InputMethodGRefFromList(pX11IMData->x11inputmethod);
        (*env)->DeleteGlobalRef(env, pX11IMData->x11inputmethod);
    }

    if (pX11IMData->lookup_buf) {
        free((void *)pX11IMData->lookup_buf);
    }

    pX11IMData->brokenImDetectionContext.isBetweenPreeditStartAndPreeditDone = False;

    free((void *)pX11IMData);
}

/*
 * Sets or unsets the focus to the given XIC.
 */
static void
setXICFocus(XIC ic, unsigned short req)
{
    if (ic == NULL) {
        (void)fprintf(stderr, "Couldn't find X Input Context\n");
        return;
    }
    if (req == 1)
        XSetICFocus(ic);
    else
        XUnsetICFocus(ic);
}

/*
 * Sets the focus window to the given XIC.
 */
static void
setXICWindowFocus(XIC ic, Window w)
{
    if (ic == NULL) {
        (void)fprintf(stderr, "Couldn't find X Input Context\n");
        return;
    }
    (void) XSetICValues(ic, XNFocusWindow, w, NULL);
}

/*
 * Invokes XmbLookupString() to get something from the XIM. It invokes
 * X11InputMethod.dispatchCommittedText() if XmbLookupString() returns
 * committed text.  This function is called from handleKeyEvent in canvas.c and
 * it's under the Motif event loop thread context.
 *
 * Buffer usage: There is a bug in XFree86-4.3.0 XmbLookupString implementation,
 * where it never returns XBufferOverflow.  We need to allocate the initial lookup buffer
 * big enough, so that the possibility that user encounters this problem is relatively
 * small.  When this bug gets fixed, we can make the initial buffer size smaller.
 * Note that XmbLookupString() sometimes produces a non-null-terminated string.
 *
 * Returns True when there is a keysym value to be handled.
 */
#define INITIAL_LOOKUP_BUF_SIZE 512

Boolean
awt_x11inputmethod_lookupString(XKeyPressedEvent *event, KeySym *keysymp, const Boolean keyPressContainsThePreeditTextOfLastXResetIC)
{
    JNIEnv *env = GetJNIEnv();
    X11InputMethodData *pX11IMData = NULL;
    KeySym keysym = NoSymbol;
    Status status;
    int mblen;
    jstring javastr;
    XIC ic;
    Boolean result = True;
    static Boolean composing = False;

    /*
      printf("lookupString: entering...\n");
     */

    if (!isX11InputMethodGRefInList(currentX11InputMethodInstance)) {
        currentX11InputMethodInstance = NULL;
        return False;
    }

    pX11IMData = getX11InputMethodData(env, currentX11InputMethodInstance);

    if (pX11IMData == NULL) {
#if defined(__linux__)
        return False;
#else
        return result;
#endif
    }

    if ((ic = pX11IMData->current_ic) == (XIC)0){
#if defined(__linux__)
        return False;
#else
        return result;
#endif
    }

    /* allocate the lookup buffer at the first invocation */
    if (pX11IMData->lookup_buf_len == 0) {
        pX11IMData->lookup_buf = (char *)malloc(INITIAL_LOOKUP_BUF_SIZE);
        if (pX11IMData->lookup_buf == NULL) {
            THROW_OUT_OF_MEMORY_ERROR();
            return result;
        }
        pX11IMData->lookup_buf_len = INITIAL_LOOKUP_BUF_SIZE;
    }

    mblen = XmbLookupString(ic, event, pX11IMData->lookup_buf,
                            pX11IMData->lookup_buf_len - 1, &keysym, &status);

    /*
     * In case of overflow, a buffer is allocated and it retries
     * XmbLookupString().
     */
    if (status == XBufferOverflow) {
        free((void *)pX11IMData->lookup_buf);
        pX11IMData->lookup_buf_len = 0;
        pX11IMData->lookup_buf = (char *)malloc(mblen + 1);
        if (pX11IMData->lookup_buf == NULL) {
            THROW_OUT_OF_MEMORY_ERROR();
            return result;
        }
        pX11IMData->lookup_buf_len = mblen + 1;
        mblen = XmbLookupString(ic, event, pX11IMData->lookup_buf,
                            pX11IMData->lookup_buf_len - 1, &keysym, &status);
    }
    pX11IMData->lookup_buf[mblen] = 0;

    /* Get keysym without taking modifiers into account first to map
     * to AWT keyCode table.
     */
    switch (status) {
    case XLookupBoth:
        if (!composing) {
            if (event->keycode != 0) {
                *keysymp = keysym;
                result = False;
                break;
            }
        }
        composing = False;
        /*FALLTHRU*/
    case XLookupChars:
        /*
        printf("lookupString: status=XLookupChars, type=%d, state=%x, keycode=%x, keysym=%x, keyPressContainsThePreeditTextOfLastXResetIC=%d\n",
               event->type, event->state, event->keycode, keysym, (int)keyPressContainsThePreeditTextOfLastXResetIC);
        */

        // JBR-3112
        // See sun.awt.X11.XToolkit#doesCurrentlyDispatchedKeyPressContainThePreeditTextOfLastXResetIC
        if (!keyPressContainsThePreeditTextOfLastXResetIC) {
            javastr = JNU_NewStringPlatform(env, (const char *)pX11IMData->lookup_buf);
            if (javastr != NULL) {
                JNU_CallMethodByName(env, NULL,
                                     currentX11InputMethodInstance,
                                     "dispatchCommittedText",
                                     "(Ljava/lang/String;J)V",
                                     javastr,
                                     event->time);
            }
        }
        break;

    case XLookupKeySym:
        /*
        printf("lookupString: status=XLookupKeySym, type=%d, state=%x, keycode=%x, keysym=%x\n",
               event->type, event->state, event->keycode, keysym);
        */
        if (keysym == XK_Multi_key)
            composing = True;
        if (! composing) {
            *keysymp = keysym;
            result = False;
        }
        break;

    case XLookupNone:
        /*
        printf("lookupString: status=XLookupNone, type=%d, state=%x, keycode=%x, keysym=%x\n",
               event->type, event->state, event->keycode, keysym);
        */
        break;
    }

    return result;
}

#if defined(__linux__)
static StatusWindow *createStatusWindow(Window parent) {
    StatusWindow *statusWindow;
    XSetWindowAttributes attrib;
    unsigned long attribmask;
    Window containerWindow;
    Window status;
    Window child;
    XWindowAttributes xwa;
    XWindowAttributes xxwa;
    /* Variable for XCreateFontSet()*/
    char **mclr;
    int  mccr = 0;
    char *dsr;
    unsigned long bg, fg, light, dim;
    int x, y, off_x, off_y, xx, yy;
    unsigned int w, h, bw, depth;
    XGCValues values;
    unsigned long valuemask = 0;  /*ignore XGCvalue and use defaults*/
    int screen = 0;
    int i;
    AwtGraphicsConfigDataPtr adata;
    /*hardcode the size right now, should get the size base on font*/
    int width=80, height=22;
    Window rootWindow;
    Window *ignoreWindowPtr;
    unsigned int ignoreUnit;
    Status rc;

    rc = XGetGeometry(dpy, parent, &rootWindow, &x, &y, &w, &h, &bw, &depth);
    if (rc == 0) {
        return NULL;
    }

    attrib.override_redirect = True;
    attribmask = CWOverrideRedirect;
    rc = XGetWindowAttributes(dpy, parent, &xwa);
    if (rc == 0) {
        return NULL;
    }
    bw = 2; /*xwa.border_width does not have the correct value*/

    if (xwa.screen != NULL) {
        screen = XScreenNumberOfScreen(xwa.screen);
    }
    adata = getDefaultConfig(screen);
    if (NULL == adata || NULL == adata->AwtColorMatch) {
        return NULL;
    }
    bg    = adata->AwtColorMatch(255, 255, 255, adata);
    fg    = adata->AwtColorMatch(0, 0, 0, adata);
    light = adata->AwtColorMatch(195, 195, 195, adata);
    dim   = adata->AwtColorMatch(128, 128, 128, adata);

    /*compare the size difference between parent container
      and shell widget, the diff should be the border frame
      and title bar height (?)*/

    XQueryTree( dpy,
                parent,
                &rootWindow,
                &containerWindow,
                &ignoreWindowPtr,
                &ignoreUnit);
    XGetWindowAttributes(dpy, containerWindow, &xxwa);

    off_x = (xxwa.width - xwa.width) / 2;
    off_y = xxwa.height - xwa.height - off_x; /*it's magic:-) */

    /*get the size of root window*/
    XGetWindowAttributes(dpy, rootWindow, &xxwa);

    XTranslateCoordinates(dpy,
                          parent, xwa.root,
                          xwa.x, xwa.y,
                          &x, &y,
                          &child);
    xx = x - off_x;
    yy = y + xwa.height - off_y;
    if (xx < 0 ){
        xx = 0;
    }
    if (xx + width > xxwa.width) {
        xx = xxwa.width - width;
    }
    if (yy + height > xxwa.height) {
        yy = xxwa.height - height;
    }

    status =  XCreateWindow(dpy,
                            xwa.root,
                            xx, yy,
                            width, height,
                            0,
                            xwa.depth,
                            InputOutput,
                            adata->awt_visInfo.visual,
                            attribmask, &attrib);
    XSelectInput(dpy, status,
                 ExposureMask | StructureNotifyMask | EnterWindowMask |
                 LeaveWindowMask | VisibilityChangeMask);
    statusWindow = (StatusWindow*) calloc(1, sizeof(StatusWindow));
    if (statusWindow == NULL){
        THROW_OUT_OF_MEMORY_ERROR();
        return NULL;
    }
    statusWindow->w = status;
    //12, 13-point fonts
    statusWindow->fontset = XCreateFontSet(dpy,
                                           "-*-*-medium-r-normal-*-*-120-*-*-*-*," \
                                           "-*-*-medium-r-normal-*-*-130-*-*-*-*",
                                           &mclr, &mccr, &dsr);
    /* In case we didn't find the font set, release the list of missing characters */
    if (mccr > 0) {
        XFreeStringList(mclr);
    }
    statusWindow->parent = parent;
    statusWindow->on  = False;
    statusWindow->x = x;
    statusWindow->y = y;
    statusWindow->width = xwa.width;
    statusWindow->height = xwa.height;
    statusWindow->off_x = off_x;
    statusWindow->off_y = off_y;
    statusWindow->bWidth  = bw;
    statusWindow->statusH = height;
    statusWindow->statusW = width;
    statusWindow->rootH = xxwa.height;
    statusWindow->rootW = xxwa.width;
    statusWindow->lightGC = XCreateGC(dpy, status, valuemask, &values);
    XSetForeground(dpy, statusWindow->lightGC, light);
    statusWindow->dimGC = XCreateGC(dpy, status, valuemask, &values);
    XSetForeground(dpy, statusWindow->dimGC, dim);
    statusWindow->fgGC = XCreateGC(dpy, status, valuemask, &values);
    XSetForeground(dpy, statusWindow->fgGC, fg);
    statusWindow->bgGC = XCreateGC(dpy, status, valuemask, &values);
    XSetForeground(dpy, statusWindow->bgGC, bg);
    return statusWindow;
}

/* This method is to turn off or turn on the status window. */
static void onoffStatusWindow(X11InputMethodData* pX11IMData,
                                Window parent,
                                Bool ON){
    XWindowAttributes xwa;
    Window child;
    int x, y;
    StatusWindow *statusWindow = NULL;

    if (NULL == currentX11InputMethodInstance ||
        NULL == pX11IMData ||
        NULL == (statusWindow =  pX11IMData->statusWindow)){
        return;
    }

    if (ON == False) {
        XUnmapWindow(dpy, statusWindow->w);
        statusWindow->on = False;
        return;
    }
    parent = JNU_CallMethodByName(GetJNIEnv(), NULL, pX11IMData->x11inputmethod,
                                  "getCurrentParentWindow",
                                  "()J").j;
    if (statusWindow->parent != parent) {
        statusWindow->parent = parent;
    }
    XGetWindowAttributes(dpy, parent, &xwa);
    XTranslateCoordinates(dpy,
                          parent, xwa.root,
                          xwa.x, xwa.y,
                          &x, &y,
                          &child);
    if (statusWindow->x != x ||
        statusWindow->y != y ||
        statusWindow->height != xwa.height)
    {
        statusWindow->x = x;
        statusWindow->y = y;
        statusWindow->height = xwa.height;
        x = statusWindow->x - statusWindow->off_x;
        y = statusWindow->y + statusWindow->height - statusWindow->off_y;
        if (x < 0 ) {
            x = 0;
        }
        if (x + statusWindow->statusW > statusWindow->rootW) {
            x = statusWindow->rootW - statusWindow->statusW;
        }
        if (y + statusWindow->statusH > statusWindow->rootH) {
            y = statusWindow->rootH - statusWindow->statusH;
        }
        XMoveWindow(dpy, statusWindow->w, x, y);
    }
    statusWindow->on = True;
    XMapWindow(dpy, statusWindow->w);
}

void paintStatusWindow(StatusWindow *statusWindow){
    Window  win  = statusWindow->w;
    GC  lightgc = statusWindow->lightGC;
    GC  dimgc = statusWindow->dimGC;
    GC  bggc = statusWindow->bgGC;
    GC  fggc = statusWindow->fgGC;

    int width = statusWindow->statusW;
    int height = statusWindow->statusH;
    int bwidth = statusWindow->bWidth;
    XFillRectangle(dpy, win, bggc, 0, 0, width, height);
    /* draw border */
    XDrawLine(dpy, win, fggc, 0, 0, width, 0);
    XDrawLine(dpy, win, fggc, 0, height-1, width-1, height-1);
    XDrawLine(dpy, win, fggc, 0, 0, 0, height-1);
    XDrawLine(dpy, win, fggc, width-1, 0, width-1, height-1);

    XDrawLine(dpy, win, lightgc, 1, 1, width-bwidth, 1);
    XDrawLine(dpy, win, lightgc, 1, 1, 1, height-2);
    XDrawLine(dpy, win, lightgc, 1, height-2, width-bwidth, height-2);
    XDrawLine(dpy, win, lightgc, width-bwidth-1, 1, width-bwidth-1, height-2);

    XDrawLine(dpy, win, dimgc, 2, 2, 2, height-3);
    XDrawLine(dpy, win, dimgc, 2, height-3, width-bwidth-1, height-3);
    XDrawLine(dpy, win, dimgc, 2, 2, width-bwidth-2, 2);
    XDrawLine(dpy, win, dimgc, width-bwidth, 2, width-bwidth, height-3);
    if (statusWindow->fontset) {
        XmbDrawString(dpy, win, statusWindow->fontset, fggc,
                      bwidth + 2, height - bwidth - 4,
                      statusWindow->status,
                      strlen(statusWindow->status));
    } else {
        /*too bad we failed to create a fontset for this locale*/
        XDrawString(dpy, win, fggc, bwidth + 2, height - bwidth - 4,
                    "[InputMethod ON]", strlen("[InputMethod ON]"));
    }
}

static void adjustStatusWindow(Window shell) {
    JNIEnv *env = GetJNIEnv();
    X11InputMethodData *pX11IMData = NULL;
    StatusWindow *statusWindow;

    if (NULL == currentX11InputMethodInstance
        || !isX11InputMethodGRefInList(currentX11InputMethodInstance)
        || NULL == (pX11IMData = getX11InputMethodData(env,currentX11InputMethodInstance))
        || NULL == (statusWindow = pX11IMData->statusWindow)
        || !statusWindow->on)
    {
        return;
    }

    {
        XWindowAttributes xwa;
        int x, y;
        Window child;
        XGetWindowAttributes(dpy, shell, &xwa);
        XTranslateCoordinates(dpy,
                              shell, xwa.root,
                              xwa.x, xwa.y,
                              &x, &y,
                              &child);
        if (statusWindow->x != x
            || statusWindow->y != y
            || statusWindow->height != xwa.height){
          statusWindow->x = x;
          statusWindow->y = y;
          statusWindow->height = xwa.height;

          x = statusWindow->x - statusWindow->off_x;
          y = statusWindow->y + statusWindow->height - statusWindow->off_y;
          if (x < 0 ) {
              x = 0;
          }
          if (x + statusWindow->statusW > statusWindow->rootW){
              x = statusWindow->rootW - statusWindow->statusW;
          }
          if (y + statusWindow->statusH > statusWindow->rootH){
              y = statusWindow->rootH - statusWindow->statusH;
          }
          XMoveWindow(dpy, statusWindow->w, x, y);
        }
    }
}
#endif  /* __linux__ */


// ===================================================== JBR-2460 =====================================================

/**
 * Checks whether the client's new implementation is enabled.
 *
 * @return True if the client's new implementation is enabled ; False otherwise.
 */
static Bool jbNewXimClient_isEnabled();

/**
 * A successor of createXIC(JNIEnv*, X11InputMethodData*, Window).
 */
static Bool jbNewXimClient_initializeXICs(
    JNIEnv *env,
    XIM xInputMethodConnection,
    X11InputMethodData *pX11IMData,
    Window window,
    Bool preferBelowTheSpot
);

// ====================================================================================================================


// TODO: update the docs
/*
 * Creates two XICs, one for active clients and the other for passive
 * clients. All information on those XICs are stored in the
 * X11InputMethodData given by the pX11IMData parameter.
 *
 * For active clients: Try to use preedit callback to support
 * on-the-spot. If tc is not null, the XIC to be created will
 * share the Status Area with Motif widgets (TextComponents). If the
 * preferable styles can't be used, fallback to root-window styles. If
 * root-window styles failed, fallback to None styles.
 *
 * For passive clients: Try to use root-window styles. If failed,
 * fallback to None styles.
 */
static Bool
createXIC(JNIEnv * env, X11InputMethodData *pX11IMData, Window w, Bool preferBelowTheSpot)
{
    if (jbNewXimClient_isEnabled() && jbNewXimClient_initializeXICs(env, X11im, pX11IMData, w, preferBelowTheSpot)) {
        return True;
    }

    XVaNestedList preedit = NULL;
    XVaNestedList status = NULL;
    XIMStyle on_the_spot_styles = XIMPreeditCallbacks,
             active_styles = 0,
             passive_styles = 0,
             no_styles = 0;
    XIMCallback *callbacks;
    unsigned short i;
    XIMStyles *im_styles;
    char *ret = NULL;

    if (X11im == NULL) {
        return False;
    }
    if (!w) {
        return False;
    }

    ret = XGetIMValues(X11im, XNQueryInputStyle, &im_styles, NULL);

    if (ret != NULL) {
        jio_fprintf(stderr,"XGetIMValues: %s\n",ret);
        return FALSE ;
    }

    on_the_spot_styles |= XIMStatusNothing;

#if defined(__linux__)
    /*kinput does not support XIMPreeditCallbacks and XIMStatusArea
      at the same time, so use StatusCallback to draw the status
      ourself
    */
    for (i = 0; i < im_styles->count_styles; i++) {
        if (im_styles->supported_styles[i] == (XIMPreeditCallbacks | XIMStatusCallbacks)) {
            on_the_spot_styles = (XIMPreeditCallbacks | XIMStatusCallbacks);
            break;
        }
    }
#endif /* __linux__ */

    for (i = 0; i < im_styles->count_styles; i++) {
        active_styles |= im_styles->supported_styles[i] & on_the_spot_styles;
        passive_styles |= im_styles->supported_styles[i] & ROOT_WINDOW_STYLES;
        no_styles |= im_styles->supported_styles[i] & NO_STYLES;
    }

    XFree(im_styles);

    if (active_styles != on_the_spot_styles) {
        if (passive_styles == ROOT_WINDOW_STYLES)
            active_styles = passive_styles;
        else {
            if (no_styles == NO_STYLES)
                active_styles = passive_styles = NO_STYLES;
            else
                active_styles = passive_styles = 0;
        }
    } else {
        if (passive_styles != ROOT_WINDOW_STYLES) {
            if (no_styles == NO_STYLES)
                active_styles = passive_styles = NO_STYLES;
            else
                active_styles = passive_styles = 0;
        }
    }

    jbNewXimClient_setInputContextFields(&pX11IMData->ic_active, NULL, 0, NULL, NULL, NULL, NULL);
    jbNewXimClient_setInputContextFields(&pX11IMData->ic_passive, NULL, 0, NULL, NULL, NULL, NULL);

    if (active_styles == on_the_spot_styles) {
        pX11IMData->ic_passive.xic = XCreateIC(X11im,
                                   XNClientWindow, w,
                                   XNFocusWindow, w,
                                   XNInputStyle, passive_styles,
                                   NULL);
        pX11IMData->ic_passive.inputStyle = passive_styles;

        callbacks = (XIMCallback *)malloc(sizeof(XIMCallback) * NCALLBACKS);
        if (callbacks == (XIMCallback *)NULL)
            return False;
        pX11IMData->ic_active.preeditAndStatusCallbacks = ( XIMCallback(*)[NCALLBACKS] ) callbacks;

        for (i = 0; i < NCALLBACKS; i++, callbacks++) {
            callbacks->client_data = (XPointer) pX11IMData->x11inputmethod;
            callbacks->callback = callback_funcs[i];
        }

        callbacks = (XIMCallback *)pX11IMData->ic_active.preeditAndStatusCallbacks;
        preedit = (XVaNestedList)XVaCreateNestedList(0,
                        XNPreeditStartCallback, &callbacks[PreeditStartIndex],
                        XNPreeditDoneCallback,  &callbacks[PreeditDoneIndex],
                        XNPreeditDrawCallback,  &callbacks[PreeditDrawIndex],
                        XNPreeditCaretCallback, &callbacks[PreeditCaretIndex],
                        NULL);
        if (preedit == (XVaNestedList)NULL)
            goto err;
#if defined(__linux__)
        /*always try XIMStatusCallbacks for active client...*/
        {
            status = (XVaNestedList)XVaCreateNestedList(0,
                        XNStatusStartCallback, &callbacks[StatusStartIndex],
                        XNStatusDoneCallback,  &callbacks[StatusDoneIndex],
                        XNStatusDrawCallback, &callbacks[StatusDrawIndex],
                        NULL);

            if (status == NULL)
                goto err;
            pX11IMData->statusWindow = createStatusWindow(w);
            pX11IMData->ic_active.xic = XCreateIC(X11im,
                                              XNClientWindow, w,
                                              XNFocusWindow, w,
                                              XNInputStyle, active_styles,
                                              XNPreeditAttributes, preedit,
                                              XNStatusAttributes, status,
                                              NULL);
            pX11IMData->ic_active.inputStyle = active_styles;
            XFree((void *)status);
            XFree((void *)preedit);
        }
#else /* !__linux__ */
        pX11IMData->ic_active.xic = XCreateIC(X11im,
                                          XNClientWindow, w,
                                          XNFocusWindow, w,
                                          XNInputStyle, active_styles,
                                          XNPreeditAttributes, preedit,
                                          NULL);
        pX11IMData->ic_active.inputStyle = active_styles;
        XFree((void *)preedit);
#endif /* __linux__ */
    } else {
        pX11IMData->ic_active.xic = XCreateIC(X11im,
                                          XNClientWindow, w,
                                          XNFocusWindow, w,
                                          XNInputStyle, active_styles,
                                          NULL);
        pX11IMData->ic_active.inputStyle = active_styles;
        pX11IMData->ic_passive = pX11IMData->ic_active;
    }

    if (pX11IMData->ic_active.xic == (XIC)0
        || pX11IMData->ic_passive.xic == (XIC)0) {
        return False;
    }

    /*
     * Use commit string call back if possible.
     * This will ensure the correct order of preedit text and commit text
     */
    {
        XIMCallback cb;
        cb.client_data = (XPointer) pX11IMData->x11inputmethod;
        cb.callback = (XIMProc) CommitStringCallback;
        XSetICValues (pX11IMData->ic_active.xic, XNCommitStringCallback, &cb, NULL);
        if (pX11IMData->ic_active.xic != pX11IMData->ic_passive.xic) {
            XSetICValues (pX11IMData->ic_passive.xic, XNCommitStringCallback, &cb, NULL);
        }
    }

    // The code set the IC mode that the preedit state is not initialized
    // at XmbResetIC.  This attribute can be set at XCreateIC.  I separately
    // set the attribute to avoid the failure of XCreateIC at some platform
    // which does not support the attribute.
    if (pX11IMData->ic_active.xic != 0)
        XSetICValues(pX11IMData->ic_active.xic,
                     XNResetState, XIMInitialState,
                     NULL);
    if (pX11IMData->ic_passive.xic != 0
            && pX11IMData->ic_active.xic != pX11IMData->ic_passive.xic)
        XSetICValues(pX11IMData->ic_passive.xic,
                     XNResetState, XIMInitialState,
                     NULL);

    pX11IMData->brokenImDetectionContext.isBetweenPreeditStartAndPreeditDone = False;

    /* Add the global reference object to X11InputMethod to the list. */
    addToX11InputMethodGRefList(pX11IMData->x11inputmethod);

    /* Unset focus to avoid unexpected IM on */
    setXICFocus(pX11IMData->ic_active.xic, False);
    if (pX11IMData->ic_active.xic != pX11IMData->ic_passive.xic)
        setXICFocus(pX11IMData->ic_passive.xic, False);

    return True;

 err:
    if (preedit)
        XFree((void *)preedit);
    THROW_OUT_OF_MEMORY_ERROR();
    return False;
}

static int
PreeditStartCallback(XIC ic, XPointer client_data, XPointer call_data)
{
    /* printf("Native: PreeditStartCallback(%p, %p, %p)\n", ic, client_data, call_data); */

    JNIEnv * const env = GetJNIEnv();

    AWT_LOCK();

    jobject javaInputMethodGRef = (jobject)client_data;
    if (!isX11InputMethodGRefInList(javaInputMethodGRef)) {
        goto finally;
    }

    X11InputMethodData * const pX11IMData = getX11InputMethodData(env, javaInputMethodGRef);
    if (pX11IMData == NULL) {
        goto finally;
    }

    pX11IMData->brokenImDetectionContext.isBetweenPreeditStartAndPreeditDone = True;

 finally:
    AWT_UNLOCK();
    return -1;
}

static void
PreeditDoneCallback(XIC ic, XPointer client_data, XPointer call_data)
{
    /* printf("Native: PreeditDoneCallback(%p, %p, %p)\n", ic, client_data, call_data); */

    JNIEnv * const env = GetJNIEnv();

    AWT_LOCK();

    jobject javaInputMethodGRef = (jobject)client_data;
    if (!isX11InputMethodGRefInList(javaInputMethodGRef)) {
        goto finally;
    }

    X11InputMethodData * const pX11IMData = getX11InputMethodData(env, javaInputMethodGRef);
    if (pX11IMData == NULL) {
        goto finally;
    }

    pX11IMData->brokenImDetectionContext.isBetweenPreeditStartAndPreeditDone = False;

 finally:
    AWT_UNLOCK();
    return;
}

/*
 * Translate the preedit draw callback items to Java values and invoke
 * X11InputMethod.dispatchComposedText().
 *
 * client_data: X11InputMethod object
 */
static void
PreeditDrawCallback(XIC ic, XPointer client_data,
                    XIMPreeditDrawCallbackStruct *pre_draw)
{
    /* printf("Native: PreeditDrawCallback(%p, %p, %p)\n", ic, client_data, pre_draw); */

    JNIEnv *env = GetJNIEnv();
    X11InputMethodData *pX11IMData = NULL;
    jmethodID x11imMethodID;

    XIMText *text;
    jstring javastr = NULL;
    jintArray style = NULL;

    /* printf("Native: PreeditDrawCallback() \n"); */
    if (pre_draw == NULL) {
        return;
    }
    AWT_LOCK();
    if (!isX11InputMethodGRefInList((jobject)client_data)) {
        if ((jobject)client_data == currentX11InputMethodInstance) {
            currentX11InputMethodInstance = NULL;
        }
        goto finally;
    }
    if ((pX11IMData = getX11InputMethodData(env, (jobject)client_data)) == NULL) {
        goto finally;
    }

    if ((text = pre_draw->text) != NULL) {
        if (text->string.multi_byte != NULL) {
            if (pre_draw->text->encoding_is_wchar == False) {
                javastr = JNU_NewStringPlatform(env, (const char *)text->string.multi_byte);
                if (javastr == NULL) {
                    goto finally;
                }
            } else {
                char *mbstr = wcstombsdmp(text->string.wide_char, text->length);
                if (mbstr == NULL) {
                    goto finally;
                }
                javastr = JNU_NewStringPlatform(env, (const char *)mbstr);
                free(mbstr);
                if (javastr == NULL) {
                    goto finally;
                }
            }
        }
        if (text->feedback != NULL) {
            int cnt;
            jint *tmpstyle;

            style = (*env)->NewIntArray(env, text->length);
            if (JNU_IsNull(env, style)) {
                (*env)->ExceptionClear(env);
                THROW_OUT_OF_MEMORY_ERROR();
                goto finally;
            }

            if (sizeof(XIMFeedback) == sizeof(jint)) {
                /*
                 * Optimization to avoid copying the array
                 */
                (*env)->SetIntArrayRegion(env, style, 0,
                                          text->length, (jint *)text->feedback);
            } else {
                tmpstyle  = (jint *)malloc(sizeof(jint)*(text->length));
                if (tmpstyle == (jint *) NULL) {
                    THROW_OUT_OF_MEMORY_ERROR();
                    goto finally;
                }
                for (cnt = 0; cnt < (int)text->length; cnt++)
                        tmpstyle[cnt] = text->feedback[cnt];
                (*env)->SetIntArrayRegion(env, style, 0,
                                          text->length, (jint *)tmpstyle);
                free(tmpstyle);
            }
        }
    }
    JNU_CallMethodByName(env, NULL, pX11IMData->x11inputmethod,
                         "dispatchComposedText",
                         "(Ljava/lang/String;[IIIIJ)V",
                         javastr,
                         style,
                         (jint)pre_draw->chg_first,
                         (jint)pre_draw->chg_length,
                         (jint)pre_draw->caret,
                         awt_util_nowMillisUTC());
finally:
    AWT_UNLOCK();
    return;
}

static void
PreeditCaretCallback(XIC ic, XPointer client_data,
                     XIMPreeditCaretCallbackStruct *pre_caret)
{
    /*ARGSUSED*/
    /* printf("Native: PreeditCaretCallback(%p, %p, %p)\n", ic, client_data, pre_caret); */
}

#if defined(__linux__)
static void
StatusStartCallback(XIC ic, XPointer client_data, XPointer call_data)
{
    /*ARGSUSED*/
    /*printf("Native: StatusStartCallback(%p, %p, %p)\n", ic, client_data, call_data);  */
}

static void
StatusDoneCallback(XIC ic, XPointer client_data, XPointer call_data)
{
    /*ARGSUSED*/
    /*printf("Native: StatusDoneCallback(%p, %p, %p)\n", ic, client_data, call_data); */
    JNIEnv *env = GetJNIEnv();
    X11InputMethodData *pX11IMData = NULL;
    StatusWindow *statusWindow;

    AWT_LOCK();

    if (!isX11InputMethodGRefInList((jobject)client_data)) {
        if ((jobject)client_data == currentX11InputMethodInstance) {
            currentX11InputMethodInstance = NULL;
        }
        goto finally;
    }

    if (NULL == (pX11IMData = getX11InputMethodData(env, (jobject)client_data))
        || NULL == (statusWindow = pX11IMData->statusWindow)){
        goto finally;
    }
    currentX11InputMethodInstance = (jobject)client_data;

    onoffStatusWindow(pX11IMData, 0, False);

 finally:
    AWT_UNLOCK();
}

static void
StatusDrawCallback(XIC ic, XPointer client_data,
                     XIMStatusDrawCallbackStruct *status_draw)
{
    /*ARGSUSED*/
    /*printf("Native: StatusDrawCallback(%p, %p, %p)\n", ic, client_data, status_draw); */
    JNIEnv *env = GetJNIEnv();
    X11InputMethodData *pX11IMData = NULL;
    StatusWindow *statusWindow;

    AWT_LOCK();

    if (!isX11InputMethodGRefInList((jobject)client_data)) {
        if ((jobject)client_data == currentX11InputMethodInstance) {
            currentX11InputMethodInstance = NULL;
        }
        goto finally;
    }

    if (NULL == (pX11IMData = getX11InputMethodData(env, (jobject)client_data))
        || NULL == (statusWindow = pX11IMData->statusWindow)){
        goto finally;
    }
    currentX11InputMethodInstance = (jobject)client_data;

    if (status_draw->type == XIMTextType) {
        XIMText *text = (status_draw->data).text;
        if (text != NULL) {
            if (text->string.multi_byte != NULL) {
                strncpy(statusWindow->status, text->string.multi_byte, MAX_STATUS_LEN);
                statusWindow->status[MAX_STATUS_LEN - 1] = '\0';
            } else {
                char *mbstr = wcstombsdmp(text->string.wide_char, text->length);
                if (mbstr == NULL) {
                    goto finally;
                }
                strncpy(statusWindow->status, mbstr, MAX_STATUS_LEN);
                statusWindow->status[MAX_STATUS_LEN - 1] = '\0';
                free(mbstr);
            }
            statusWindow->on = True;
            onoffStatusWindow(pX11IMData, statusWindow->parent, True);
            paintStatusWindow(statusWindow);
        } else {
            statusWindow->on = False;
            /*just turnoff the status window
            paintStatusWindow(statusWindow);
            */
            onoffStatusWindow(pX11IMData, 0, False);
        }
    }

 finally:
    AWT_UNLOCK();
}
#endif /* __linux__ */

static void CommitStringCallback(XIC ic, XPointer client_data, XPointer call_data) {
    /* printf("Native: CommitStringCallback(%p, %p, %p)\n", ic, client_data, call_data); */

    JNIEnv *env = GetJNIEnv();
    XIMText * text = (XIMText *)call_data;
    X11InputMethodData *pX11IMData = NULL;
    jstring javastr;

    AWT_LOCK();

    if (!isX11InputMethodGRefInList((jobject)client_data)) {
        if ((jobject)client_data == currentX11InputMethodInstance) {
            currentX11InputMethodInstance = NULL;
        }
        goto finally;
    }

    if ((pX11IMData = getX11InputMethodData(env, (jobject)client_data)) == NULL) {
        goto finally;
    }
    currentX11InputMethodInstance = (jobject)client_data;

    if (text->encoding_is_wchar == False) {
        javastr = JNU_NewStringPlatform(env, (const char *)text->string.multi_byte);
    } else {
        char *mbstr = wcstombsdmp(text->string.wide_char, text->length);
        if (mbstr == NULL) {
            goto finally;
        }
        javastr = JNU_NewStringPlatform(env, (const char *)mbstr);
        free(mbstr);
    }

    if (javastr != NULL) {
        JNU_CallMethodByName(env, NULL,
                                 pX11IMData->x11inputmethod,
                                 "dispatchCommittedText",
                                 "(Ljava/lang/String;J)V",
                                 javastr,
                                 awt_util_nowMillisUTC());
    }
 finally:
    AWT_UNLOCK();
}

static void OpenXIMCallback(Display *display, XPointer client_data, XPointer call_data) {
    XIMCallback ximCallback;

    X11im = XOpenIM(display, NULL, NULL, NULL);
    if (X11im == NULL) {
        return;
    }

    ximCallback.callback = (XIMProc)DestroyXIMCallback;
    ximCallback.client_data = NULL;
    XSetIMValues(X11im, XNDestroyCallback, &ximCallback, NULL);
}

static void DestroyXIMCallback(XIM im, XPointer client_data, XPointer call_data) {
    /* mark that XIM server was destroyed */
    X11im = NULL;
    JNIEnv* env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);

    AWT_LOCK();
    /* free the old pX11IMData and set it to null. this also avoids crashing
     * the jvm if the XIM server reappears */
    while (x11InputMethodGRefListHead != NULL) {
        if (getX11InputMethodData(env,
                x11InputMethodGRefListHead->inputMethodGRef) == NULL) {
            /* Clear possible exceptions
             */
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionDescribe(env);
                (*env)->ExceptionClear(env);
            }
        }
    }
    AWT_UNLOCK();
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_X11_XInputMethod_openXIMNative(JNIEnv *env,
                                            jobject this,
                                            jlong display)
{
    Bool registered;

    AWT_LOCK();

    dpy = (Display *)jlong_to_ptr(display);

/* Use IMInstantiate call back only on Linux, as there is a bug in Solaris
   (4768335)
*/
#if defined(__linux__)
    registered = XRegisterIMInstantiateCallback(dpy, NULL, NULL,
                     NULL, (XIDProc)OpenXIMCallback, NULL);
    if (!registered) {
        /* directly call openXIM callback */
#endif
        OpenXIMCallback(dpy, NULL, NULL);
#if defined(__linux__)
    }
#endif

    AWT_UNLOCK();

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_X11_XInputMethod_createXICNative(JNIEnv *env,
                                              jobject this,
                                              jlong window,
                                              jboolean preferBelowTheSpot)
{
    X11InputMethodData *pX11IMData;
    jobject globalRef;
    XIC ic;

    AWT_LOCK();

    if (!window) {
        JNU_ThrowNullPointerException(env, "NullPointerException");
        AWT_UNLOCK();
        return JNI_FALSE;
    }

    pX11IMData = (X11InputMethodData *) calloc(1, sizeof(X11InputMethodData));
    if (pX11IMData == NULL) {
        THROW_OUT_OF_MEMORY_ERROR();
        AWT_UNLOCK();
        return JNI_FALSE;
    }

    globalRef = (*env)->NewGlobalRef(env, this);
    pX11IMData->x11inputmethod = globalRef;
#if defined(__linux__)
    pX11IMData->statusWindow = NULL;
#endif /* __linux__ */

    pX11IMData->lookup_buf = 0;
    pX11IMData->lookup_buf_len = 0;

    if (createXIC(env, pX11IMData, (Window)window, (preferBelowTheSpot == JNI_TRUE) ? True : False) == False) {
        destroyX11InputMethodData((JNIEnv *) NULL, pX11IMData);
        pX11IMData = (X11InputMethodData *) NULL;
        if ((*env)->ExceptionCheck(env)) {
            goto finally;
        }
    }

    setX11InputMethodData(env, this, pX11IMData);

finally:
    AWT_UNLOCK();
    return (pX11IMData != NULL);
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_X11_XInputMethod_recreateXICNative(JNIEnv *env,
                                              jobject this,
                                              jlong window, jlong pData, jint ctxid,
                                              jboolean preferBelowTheSpot)
{
    // NOTE: must be called under AWT_LOCK
    X11InputMethodData * pX11IMData = (X11InputMethodData *)pData;
    jboolean result = createXIC(env, pX11IMData, window, (preferBelowTheSpot == JNI_TRUE) ? True : False);
    if (result) {
        if (ctxid == 1)
            pX11IMData->current_ic = pX11IMData->ic_active.xic;
        else if (ctxid == 2)
            pX11IMData->current_ic = pX11IMData->ic_passive.xic;
    }
    return result;
}

JNIEXPORT int JNICALL
Java_sun_awt_X11_XInputMethod_releaseXICNative(JNIEnv *env,
                                              jobject this,
                                              jlong pData)
{
    // NOTE: must be called under AWT_LOCK
    X11InputMethodData * pX11IMData = (X11InputMethodData *)pData;
    int result = 0;
    if (pX11IMData->current_ic == pX11IMData->ic_active.xic)
        result = 1;
    else if (pX11IMData->current_ic == pX11IMData->ic_passive.xic)
        result = 2;
    pX11IMData->current_ic = NULL;
    destroyXInputContexts(pX11IMData);
    return result;
}


JNIEXPORT void JNICALL
Java_sun_awt_X11_XInputMethod_setXICFocusNative(JNIEnv *env,
                                                jobject this,
                                                jlong w,
                                                jboolean req,
                                                jboolean active)
{
    X11InputMethodData *pX11IMData;
    AWT_LOCK();
    pX11IMData = getX11InputMethodData(env, this);
    if (pX11IMData == NULL) {
        AWT_UNLOCK();
        return;
    }

    if (req) {
        if (!w) {
            AWT_UNLOCK();
            return;
        }
        pX11IMData->current_ic = active ?
                        pX11IMData->ic_active.xic : pX11IMData->ic_passive.xic;
        /*
         * On Solaris2.6, setXICWindowFocus() has to be invoked
         * before setting focus.
         */
        setXICWindowFocus(pX11IMData->current_ic, w);
        setXICFocus(pX11IMData->current_ic, req);
        currentX11InputMethodInstance = pX11IMData->x11inputmethod;
        currentFocusWindow =  w;
#if defined(__linux__)
        if (active && pX11IMData->statusWindow && pX11IMData->statusWindow->on)
            onoffStatusWindow(pX11IMData, w, True);
#endif
    } else {
        currentX11InputMethodInstance = NULL;
        currentFocusWindow = 0;
#if defined(__linux__)
        onoffStatusWindow(pX11IMData, 0, False);
        if (pX11IMData->current_ic != NULL)
#endif
        setXICFocus(pX11IMData->current_ic, req);

        pX11IMData->current_ic = (XIC)0;
    }

    XFlush(dpy);
    AWT_UNLOCK();
}


/*
 * Class:     sun_awt_X11_XInputMethod_BrokenImDetectionContext
 * Method:    obtainCurrentXimNativeDataPtr
 * Signature: ()J
 *
 * NOTE: MUST BE CALLED WITHIN AWT_LOCK
 */
JNIEXPORT jlong JNICALL Java_sun_awt_X11_XInputMethod_00024BrokenImDetectionContext_obtainCurrentXimNativeDataPtr
  (JNIEnv *env, jclass cls)
{
    jlong result = 0;

    if (isX11InputMethodGRefInList(currentX11InputMethodInstance)) {
        X11InputMethodData * const pX11IMData = getX11InputMethodData(env, currentX11InputMethodInstance);
        result = ptr_to_jlong(pX11IMData);
    }

    return result;
}

/*
 * Class:     sun_awt_X11_XInputMethod_BrokenImDetectionContext
 * Method:    isCurrentXicPassive
 * Signature: (J)Z
 *
 * NOTE: MUST BE CALLED WITHIN AWT_LOCK
 */
JNIEXPORT jboolean JNICALL Java_sun_awt_X11_XInputMethod_00024BrokenImDetectionContext_isCurrentXicPassive
  (JNIEnv *env, jclass cls, jlong ximNativeDataPtr)
{
    X11InputMethodData * const pX11ImData = (X11InputMethodData *)jlong_to_ptr(ximNativeDataPtr);
    if (pX11ImData == NULL) {
        return JNI_FALSE;
    }

    const jboolean result = (pX11ImData->current_ic == NULL) ? JNI_FALSE
                            : (pX11ImData->current_ic == pX11ImData->ic_passive.xic) ? JNI_TRUE
                            : JNI_FALSE;

    return result;
}

static XIMPreeditState getPreeditStateOf(XIC xic) {
#if defined(__linux__) && defined(_LP64) && !defined(_LITTLE_ENDIAN)
    // XIMPreeditState value which is used for XGetICValues must be 32bit on BigEndian XOrg's xlib
    unsigned int state = XIMPreeditUnKnown;
#else
    XIMPreeditState state = XIMPreeditUnKnown;
#endif

    XVaNestedList preeditStateAttr = XVaCreateNestedList(0, XNPreeditState, &state, NULL);
    if (preeditStateAttr == NULL) {
        return XIMPreeditUnKnown;
    }
    const char * const unsupportedAttrs = XGetICValues(xic, XNPreeditAttributes, preeditStateAttr, NULL);
    XFree((void *)preeditStateAttr);

    if (unsupportedAttrs != NULL) {
        return XIMPreeditUnKnown;
    }

    return (state == XIMPreeditEnable) ? XIMPreeditEnable
           : (state == XIMPreeditDisable) ? XIMPreeditDisable
           : XIMPreeditUnKnown;
}

/*
 * Class:     sun_awt_X11_XInputMethod_BrokenImDetectionContext
 * Method:    isDuringPreediting
 * Signature: ()I
 *
 * Returns the following values:
 * * >0 in case the IM is in preediting state;
 * *  0 in case the IM is not in preediting state;
 * * <0 in case it's unknown whether the IM is in preediting state or not.
 *
 * NOTE: MUST BE CALLED WITHIN AWT_LOCK
 */
JNIEXPORT jint JNICALL Java_sun_awt_X11_XInputMethod_00024BrokenImDetectionContext_isDuringPreediting
  (JNIEnv *env, jclass cls, jlong ximNativeDataPtr)
{
    X11InputMethodData * const pX11ImData = (X11InputMethodData *)jlong_to_ptr(ximNativeDataPtr);
    if (pX11ImData == NULL) {
        return -1;
    }

    jint result = -1;

    if (pX11ImData->brokenImDetectionContext.isBetweenPreeditStartAndPreeditDone) {
        result = 1;
    } else if (pX11ImData->current_ic != NULL) {
        const XIMPreeditState preeditState = getPreeditStateOf(pX11ImData->current_ic);
        if (preeditState == XIMPreeditEnable) {
            result = 1;
        } else if (preeditState == XIMPreeditDisable) {
            result = 0;
        }
    }

    return result;
}


/*
 * Class:     sun_awt_X11InputMethodBase
 * Method:    initIDs
 * Signature: ()V
 * This function gets called from the static initializer for
 * X11InputMethod.java to initialize the fieldIDs for fields
 * that may be accessed from C
 */
JNIEXPORT void JNICALL Java_sun_awt_X11InputMethodBase_initIDs
  (JNIEnv *env, jclass cls)
{
    x11InputMethodIDs.pData = (*env)->GetFieldID(env, cls, "pData", "J");
}

/*
 * Class:     sun_awt_X11InputMethodBase
 * Method:    turnoffStatusWindow
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_awt_X11InputMethodBase_turnoffStatusWindow
  (JNIEnv *env, jobject this)
{
#if defined(__linux__)
    X11InputMethodData *pX11IMData;
    StatusWindow *statusWindow;

    AWT_LOCK();

    if (NULL == currentX11InputMethodInstance
        || !isX11InputMethodGRefInList(currentX11InputMethodInstance)
        || NULL == (pX11IMData = getX11InputMethodData(env,currentX11InputMethodInstance))
        || NULL == (statusWindow = pX11IMData->statusWindow)
        || !statusWindow->on ){
        AWT_UNLOCK();
        return;
    }
    onoffStatusWindow(pX11IMData, 0, False);

    AWT_UNLOCK();
#endif
}

/*
 * Class:     sun_awt_X11InputMethodBase
 * Method:    disposeXIC
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_awt_X11InputMethodBase_disposeXIC
  (JNIEnv *env, jobject this)
{
    X11InputMethodData *pX11IMData = NULL;

    AWT_LOCK();
    pX11IMData = getX11InputMethodData(env, this);
    if (pX11IMData == NULL) {
        AWT_UNLOCK();
        return;
    }

    setX11InputMethodData(env, this, NULL);

    if (pX11IMData->x11inputmethod == currentX11InputMethodInstance) {
        currentX11InputMethodInstance = NULL;
        currentFocusWindow = 0;
    }
    destroyX11InputMethodData(env, pX11IMData);
    AWT_UNLOCK();
}

/*
 * Class:     sun_awt_X11InputMethodBase
 * Method:    resetXIC
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_sun_awt_X11InputMethodBase_resetXIC
  (JNIEnv *env, jobject this)
{
    X11InputMethodData *pX11IMData;
    char *xText = NULL;
    jstring jText = (jstring)0;

    AWT_LOCK();
    pX11IMData = getX11InputMethodData(env, this);
    if (pX11IMData == NULL) {
        AWT_UNLOCK();
        return jText;
    }

    if (pX11IMData->current_ic)
        xText = XmbResetIC(pX11IMData->current_ic);
    else {
        /*
         * If there is no reference to the current XIC, try to reset both XICs.
         */
        xText = XmbResetIC(pX11IMData->ic_active.xic);
        /*it may also means that the real client component does
          not have focus -- has been deactivated... its xic should
          not have the focus, bug#4284651 showes reset XIC for htt
          may bring the focus back, so de-focus it again.
        */
        setXICFocus(pX11IMData->ic_active.xic, FALSE);
        if (pX11IMData->ic_active.xic != pX11IMData->ic_passive.xic) {
            char *tmpText = XmbResetIC(pX11IMData->ic_passive.xic);
            setXICFocus(pX11IMData->ic_passive.xic, FALSE);
            if (xText == (char *)NULL && tmpText)
                xText = tmpText;
        }

    }
    if (xText != NULL) {
        jText = JNU_NewStringPlatform(env, (const char *)xText);
        XFree((void *)xText);
    }

    AWT_UNLOCK();
    return jText;
}

/*
 * Class:     sun_awt_X11InputMethodBase
 * Method:    setCompositionEnabledNative
 * Signature: (Z)Z
 *
 * This method tries to set the XNPreeditState attribute associated with the current
 * XIC to the passed in 'enable' state.
 *
 * Return JNI_TRUE if XNPreeditState attribute is successfully changed to the
 * 'enable' state; Otherwise, if XSetICValues fails to set this attribute,
 * java.lang.UnsupportedOperationException will be thrown. JNI_FALSE is returned if this
 * method fails due to other reasons.
 */
JNIEXPORT jboolean JNICALL Java_sun_awt_X11InputMethodBase_setCompositionEnabledNative
  (JNIEnv *env, jobject this, jboolean enable)
{
    X11InputMethodData *pX11IMData;
    char * ret = NULL;
    XVaNestedList   pr_atrb;
#if defined(__linux__)
    Boolean calledXSetICFocus = False;
#endif

    AWT_LOCK();
    pX11IMData = getX11InputMethodData(env, this);

    if ((pX11IMData == NULL) || (pX11IMData->current_ic == NULL)) {
        AWT_UNLOCK();
        return JNI_FALSE;
    }

#if defined(__linux__)
    if (NULL != pX11IMData->statusWindow) {
        Window focus = 0;
        int revert_to;
        Window w = 0;
        XGetInputFocus(awt_display, &focus, &revert_to);
        XGetICValues(pX11IMData->current_ic, XNFocusWindow, &w, NULL);
#if defined(_LP64) && !defined(_LITTLE_ENDIAN)
        // On 64bit BigEndian,
        // Window value may be stored on high 32bit by XGetICValues via XIM
        if (w > 0xffffffffUL) w = w >> 32;
#endif
        if (RevertToPointerRoot == revert_to
                && pX11IMData->ic_active.xic != pX11IMData->ic_passive.xic) {
            if (pX11IMData->current_ic == pX11IMData->ic_active.xic) {
                if (getParentWindow(focus) == getParentWindow(w)) {
                    XUnsetICFocus(pX11IMData->ic_active.xic);
                    calledXSetICFocus = True;
                }
            }
        }
    }
#endif
    pr_atrb = XVaCreateNestedList(0,
                  XNPreeditState, (enable ? XIMPreeditEnable : XIMPreeditDisable),
                  NULL);
    ret = XSetICValues(pX11IMData->current_ic, XNPreeditAttributes, pr_atrb, NULL);
    XFree((void *)pr_atrb);
#if defined(__linux__)
    if (calledXSetICFocus) {
        XSetICFocus(pX11IMData->ic_active.xic);
    }
#endif
    AWT_UNLOCK();

    if ((ret != 0)
            && ((strcmp(ret, XNPreeditAttributes) == 0)
            || (strcmp(ret, XNPreeditState) == 0))) {
        JNU_ThrowByName(env, "java/lang/UnsupportedOperationException", "");
    }

    return (jboolean)(ret == 0);
}

/*
 * Class:     sun_awt_X11InputMethodBase
 * Method:    isCompositionEnabledNative
 * Signature: ()Z
 *
 * This method tries to get the XNPreeditState attribute associated with the current XIC.
 *
 * Return JNI_TRUE if the XNPreeditState is successfully retrieved. Otherwise, if
 * XGetICValues fails to get this attribute, java.lang.UnsupportedOperationException
 * will be thrown. JNI_FALSE is returned if this method fails due to other reasons.
 */
JNIEXPORT jboolean JNICALL Java_sun_awt_X11InputMethodBase_isCompositionEnabledNative
  (JNIEnv *env, jobject this)
{
    X11InputMethodData *pX11IMData = NULL;
    char * ret = NULL;
    XIMPreeditState state = XIMPreeditUnKnown;

    XVaNestedList   pr_atrb;

    AWT_LOCK();
    pX11IMData = getX11InputMethodData(env, this);

    if ((pX11IMData == NULL) || (pX11IMData->current_ic == NULL)) {
        AWT_UNLOCK();
        return JNI_FALSE;
    }

    pr_atrb = XVaCreateNestedList(0, XNPreeditState, &state, NULL);
    ret = XGetICValues(pX11IMData->current_ic, XNPreeditAttributes, pr_atrb, NULL);
    XFree((void *)pr_atrb);
    AWT_UNLOCK();
#if defined(__linux__) && defined(_LP64) && !defined(_LITTLE_ENDIAN)
    // On 64bit BigEndian,
    // XIMPreeditState value may be stored on high 32bit by XGetICValues via XIM
    if (state > 0xffffffffUL) state = state >> 32;
#endif

    if ((ret != 0)
            && ((strcmp(ret, XNPreeditAttributes) == 0)
            || (strcmp(ret, XNPreeditState) == 0))) {
        JNU_ThrowByName(env, "java/lang/UnsupportedOperationException", "");
        return JNI_FALSE;
    }

    return (jboolean)(state == XIMPreeditEnable);
}

JNIEXPORT void JNICALL Java_sun_awt_X11_XInputMethod_adjustStatusWindow
  (JNIEnv *env, jobject this, jlong window)
{
#if defined(__linux__)
    AWT_LOCK();
    adjustStatusWindow(window);
    AWT_UNLOCK();
#endif
}


JNIEXPORT jboolean JNICALL Java_sun_awt_X11_XInputMethod_doesFocusedXICSupportMovingCandidatesNativeWindow
  (JNIEnv *env, jobject this)
{
    X11InputMethodData *pX11IMData = NULL;
    jboolean result = JNI_FALSE;

    if ((env == NULL) || (this == NULL)) {
        return JNI_FALSE;
    }

    AWT_LOCK();

    pX11IMData = getX11InputMethodData(env, this);
    if (pX11IMData == NULL) {
        goto finally;
    }

    if (pX11IMData->current_ic == NULL) {
        goto finally;
    }

    if (pX11IMData->current_ic == pX11IMData->ic_active.xic) {
        if ( (pX11IMData->ic_active.inputStyle & XIMPreeditPosition) == XIMPreeditPosition ) {
            result = JNI_TRUE;
        }
    } else {
        DASSERT(pX11IMData->current_ic == pX11IMData->ic_passive.xic);
        if ( (pX11IMData->ic_passive.inputStyle & XIMPreeditPosition) == XIMPreeditPosition ) {
            result = JNI_TRUE;
        }
    }

finally:
    AWT_UNLOCK();

    return result;
}


static void jbNewXimClient_moveImCandidatesWindow(XIC ic, XPoint newLocation);

JNIEXPORT void JNICALL Java_sun_awt_X11_XInputMethod_adjustCandidatesNativeWindowPosition
  (JNIEnv *env, jobject this, jint x, jint y)
{
    // Must be called under awt lock

    XPoint location = {x, y};
    XIC xic = NULL;
    X11InputMethodData *pX11IMData = getX11InputMethodData(env, this);

    if (pX11IMData == NULL) {
        return;
    }

    xic = pX11IMData->current_ic;
    if (xic == NULL) {
        jio_fprintf(stderr, "%s: xic == NULL.\n", __func__);
        return;
    }

    jbNewXimClient_moveImCandidatesWindow(xic, location);
}

#if defined(__linux__)
static Window getParentWindow(Window w)
{
    Window root=None, parent=None, *ignore_children=NULL;
    unsigned int ignore_uint=0;
    Status status = 0;

    if (w == None)
        return None;
    status = XQueryTree(dpy, w, &root, &parent, &ignore_children, &ignore_uint);
    XFree(ignore_children);
    if (status == 0)
        return None;
    return parent;
}
#endif

JNIEXPORT jboolean JNICALL
Java_sun_awt_X11InputMethod_recreateX11InputMethod(JNIEnv *env, jclass cls)
{
    if (X11im == NULL || dpy == NULL)
        return JNI_FALSE;

    Status retstat = XCloseIM(X11im);
    X11im = XOpenIM(dpy, NULL, NULL, NULL);
    if (X11im == NULL)
        return JNI_FALSE;

    XIMCallback ximCallback;
    ximCallback.callback = (XIMProc)DestroyXIMCallback;
    ximCallback.client_data = NULL;
    XSetIMValues(X11im, XNDestroyCallback, &ximCallback, NULL);
    return JNI_TRUE;
}


// ====================================================================================================================
// JBR-2460: completely new implementation of XIM client
// It uses the "over-the-spot" interaction style with the IME
//   (to be more precise, XIMPreeditPosition | XIMStatusNothing flags. XIMStatusNothing is used because it's the only
//    style supported by each of fcitx, fcitx5, iBus IMEs)
// Usage of the new client is controlled by the function jbNewXimClient_isEnabled that invokes
//   the sun.awt.X11.XInputMethod#isJbNewXimClientEnabled method
// ====================================================================================================================

/**
 * Optional features
 */
typedef struct {
    struct {
        // XNVisiblePosition
        Bool isXNVisiblePositionAvailable;
        // XNR6PreeditCallback
        Bool isXNR6PreeditCallbackAvailable;
    } ximFeatures;

    struct {
        // XNStringConversion
        Bool isXNStringConversionAvailable;
        // XNStringConversionCallback
        Bool isXNStringConversionCallbackAvailable;
        // XNResetState
        Bool isXNResetStateAvailable;
        // XNHotKey
        Bool isXNHotKeyAvailable;
        // XNPreeditState
        Bool isXNPreeditStateAvailable;
        // XNPreeditStateNotifyCallback
        Bool isXNPreeditStateNotifyCallbackAvailable;
        // XNCommitStringCallback
        Bool isXNCommitStringCallbackAvailable;
    } xicFeatures;
} jbNewXimClient_XIMFeatures;

/**
 * Asks \p inputMethod about the features it supports.
 */
static jbNewXimClient_XIMFeatures jbNewXimClient_obtainSupportedXIMFeaturesBy(XIM inputMethod);


/**
 * Obtains supported input styles by the specified input method
 * @return NULL if failed ; otherwise the returned pointer has to be freed via XFree after use
 */
static XIMStyles* jbNewXimClient_obtainSupportedInputStylesBy(XIM inputMethod);


// See https://docs.oracle.com/javase/8/docs/technotes/guides/imf/spec.html#InputStyles
// DON'T FORGET TO ADJUST THE VALUE OF THE JBNEWXIMCLIENT_COUNTOF_SUPPORTED_INPUT_STYLES IF YOU ADD/REMOVE ELEMENTS HERE
typedef enum jbNewXimClient_SupportedInputStyle {
    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_1    = XIMPreeditCallbacks | XIMStatusCallbacks,
    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_2    = XIMPreeditCallbacks | XIMStatusNothing,
    // Corresponds to jbNewXimClient_createInputContextOfPreeditPositionStatusNothing
    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_BELOW_THE_SPOT_1 = XIMPreeditPosition  | XIMStatusNothing,
    // Corresponds to jbNewXimClient_createInputContextOfPreeditNothingStatusNothing
    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ROOT_WINDOW_1    = XIMPreeditNothing   | XIMStatusNothing,
    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_NOFEEDBACK       = XIMPreeditNone      | XIMStatusNone
} jbNewXimClient_SupportedInputStyle;

enum {
    JBNEWXIMCLIENT_COUNTOF_SUPPORTED_INPUT_STYLES = 5
};

typedef struct jbNewXimClient_PrioritizedStyles {
    struct {
        jbNewXimClient_SupportedInputStyle forActiveClient;
        jbNewXimClient_SupportedInputStyle forPassiveClient;
    } combinations[JBNEWXIMCLIENT_COUNTOF_SUPPORTED_INPUT_STYLES * JBNEWXIMCLIENT_COUNTOF_SUPPORTED_INPUT_STYLES];

    // Only 0th..pairsCount-1 elements of combinations are valid
    unsigned int pairsCount;
} jbNewXimClient_PrioritizedStyles;

/**
 * Among all the XIM input styles supported by the current IME (see jbNewXimClient_obtainSupportedInputStylesBy) finds
 *   all styles supported by this implementation and forms pairs (style for an active client ; style for a passive client)
 *   in the descending order of preference.
 *
 * @param preferBelowTheSpot - allows to override the default behavior which prefers on-the-spot style over below-the-spot
 * @param allXimSupportedInputStyles - the XIM input styles to search within
 * @param allXimSupportedFeatures - all the features supported by the current IME (can be obtained via jbNewXimClient_obtainSupportedXIMFeaturesBy)
 * @return pairs (style for an active client ; style for a passive client) in the combinations field
 *         and the number of valid pairs in the pairsCount field
 */
static jbNewXimClient_PrioritizedStyles jbNewXimClient_chooseAndPrioritizeInputStyles(
    Bool preferBelowTheSpot,
    const XIMStyles *allXimSupportedInputStyles,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
);


/**
 * Creates an input context of the specified input style.
 *
 * @param style - specifies on of the styles obtained from jbNewXimClient_chooseAndPrioritizeInputStyles
 * @param window - specifies XNClientWindow XIC value
 * @param allXimSupportedFeatures - specifies all the features supported by the current IME
 *                                  (can be obtained via jbNewXimClient_obtainSupportedXIMFeaturesBy)
 * @return NULL if it failed to create an input context suitable for the specified input style
 *         ; otherwise the returned pointer has to be freed via XDestroyIC
 */
static jbNewXimClient_ExtendedInputContext jbNewXimClient_createInputContextOfStyle(
    jbNewXimClient_SupportedInputStyle style,
    JNIEnv *jEnv,
    const X11InputMethodData *pX11IMData,
    XIM xInputMethodConnection,
    Window window,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
);


static jclass XInputMethodCls = NULL;
static jmethodID isJbNewXimClientEnabledMID = NULL;
static Bool jbNewXimClient_isEnabled() {
    // Basically it just calls the java static method sun.awt.X11.XInputMethod#isJbNewXimClientEnabled()

    JNIEnv * const jniEnv = GetJNIEnv();

    if ((jniEnv == NULL) || (jniEnv == (void*)JNI_ERR)) {
        jio_fprintf(stderr, "%s: GetJNIEnv() failed (jniEnv == NULL || jniEnv == (void*)JNI_ERR).\n", __func__);
        return False;
    }

    // Looking up the method sun.awt.X11.XInputMethod#isJbNewXimClientEnabled()

    if (XInputMethodCls == NULL) {
        jclass XInputMethodClsTmp = NULL;
        const jclass XInputMethodClsLocalRef = (*jniEnv)->FindClass(jniEnv, "sun/awt/X11/XInputMethod");
        if (XInputMethodClsLocalRef == NULL) {
            jio_fprintf(stderr, "%s: failed to find the sun.awt.X11.XInputMethod class (XInputMethodClsLocalRef == NULL).\n", __func__);
            return False;
        }

        XInputMethodClsTmp = (jclass)(*jniEnv)->NewGlobalRef(jniEnv, XInputMethodClsLocalRef);
        if (XInputMethodClsTmp == NULL) {
            jio_fprintf(stderr, "%s: NewGlobalRef() failed (XInputMethodClsTmp == NULL).\n", __func__);
            return False;
        }

        XInputMethodCls = XInputMethodClsTmp;
    }

    if (isJbNewXimClientEnabledMID == NULL) {
        const jmethodID isJbNewXimClientEnabledMIDTmp =
            (*jniEnv)->GetStaticMethodID(jniEnv, XInputMethodCls, "isJbNewXimClientEnabled", "()Z");
        if (isJbNewXimClientEnabledMIDTmp == NULL) {
            jio_fprintf(stderr, "%s: GetStaticMethodID() failed (isJbNewXimClientEnabledMIDTmp == NULL).\n", __func__);
            return False;
        }

        isJbNewXimClientEnabledMID = isJbNewXimClientEnabledMIDTmp;
    }

    return ( (*jniEnv)->CallStaticBooleanMethod(jniEnv, XInputMethodCls, isJbNewXimClientEnabledMID) == JNI_TRUE )
           ? True
           : False;
}


static Bool jbNewXimClient_initializeXICs(
    JNIEnv* const env,
    const XIM xInputMethodConnection,
    X11InputMethodData* const pX11IMData,
    const Window window,
    const Bool preferBelowTheSpot
) {
    if (env == NULL) {
        jio_fprintf(stderr, "%s: env == NULL.\n", __func__);
        return False;
    }
    if (xInputMethodConnection == NULL) {
        // printf has been disabled because it pollutes stderr in environments without input methods
        //jio_fprintf(stderr, "%s: xInputMethodConnection == NULL.\n", __func__);
        return False;
    }
    if (pX11IMData == NULL) {
        jio_fprintf(stderr, "%s: pX11IMData == NULL.\n", __func__);
        return False;
    }

    Bool result = False;

    jbNewXimClient_XIMFeatures supportedXimFeatures = { 0 };
    XIMStyles* supportedXimInputStyles = NULL;

    jbNewXimClient_PrioritizedStyles inputStylesToTry = { 0 };

    jbNewXimClient_ExtendedInputContext activeClientIc;
    jbNewXimClient_ExtendedInputContext passiveClientIc;

    unsigned int i = 0;

    jbNewXimClient_setInputContextFields(&activeClientIc, NULL, 0, NULL, NULL, NULL, NULL);
    jbNewXimClient_setInputContextFields(&passiveClientIc, NULL, 0, NULL, NULL, NULL, NULL);

    // Required IC values for XCreateIC by the X protocol:
    // * XNInputStyle
    // * (When XNInputStyle includes XIMPreeditPosition) XNFontSet
    // * When XNInputStyle includes XIMPreeditCallbacks:
    //   * XNPreeditStartCallback
    //   * XNPreeditDoneCallback
    //   * XNPreeditDrawCallback
    //   * XNPreeditCaretCallback
    // * When XNInputStyle includes XIMStatusCallbacks:
    //   * XNStatusStartCallback
    //   * XNStatusDoneCallback
    //   * XNStatusDrawCallback

    // 1. Ask for supported IM features (see the docs about XNQueryIMValuesList and XNQueryICValuesList)
    // 2. Ask for supported IM styles (XIMPreedit... | XIMStatus...)
    // 3. Choose the IM styles for active and passive clients in the following descending order:
    //     a. if preferBelowTheSpot is True:
    //         1. XIMPreeditPosition
    //         2. XIMPreeditCallbacks
    //         3. XIMPreeditNothing
    //         4. XIMPreeditNone
    //     b. if preferBelowTheSpot is False:
    //         1. XIMPreeditCallbacks
    //         2. XIMPreeditPosition
    //         3. XIMPreeditNothing
    //         4. XIMPreeditNone
    // 4. Try to create an active and a passive client of the styles got from p.3

    supportedXimFeatures = jbNewXimClient_obtainSupportedXIMFeaturesBy(xInputMethodConnection);

    supportedXimInputStyles = jbNewXimClient_obtainSupportedInputStylesBy(xInputMethodConnection);
    if (supportedXimInputStyles == NULL) {
        jio_fprintf(stderr, "%s: failed to obtain input styles supported by xInputMethodConnection=%p.\n",
                    __func__, xInputMethodConnection);
        goto finally;
    }

    inputStylesToTry = jbNewXimClient_chooseAndPrioritizeInputStyles(
        preferBelowTheSpot,
        supportedXimInputStyles,
        &supportedXimFeatures
    );

    XFree(supportedXimInputStyles);
    supportedXimInputStyles = NULL;

    if (inputStylesToTry.pairsCount < 1) {
        // No acceptable styles are found
        goto finally;
    }

    // Try to create a pair of contexts for an active and a passive client respectively
    //   in descending order of preferred styles pairs
    for (i = 0; i < inputStylesToTry.pairsCount; ++i) {
        activeClientIc = jbNewXimClient_createInputContextOfStyle(
            inputStylesToTry.combinations[i].forActiveClient,
            env,
            pX11IMData,
            xInputMethodConnection,
            window,
            &supportedXimFeatures
        );
        if (activeClientIc.xic == NULL) {
            // Failed to create a context for an active client, so let's try the next pair of styles
            continue;
        }

        passiveClientIc = jbNewXimClient_createInputContextOfStyle(
            inputStylesToTry.combinations[i].forPassiveClient,
            env,
            pX11IMData,
            xInputMethodConnection,
            window,
            &supportedXimFeatures
        );
        if (passiveClientIc.xic == NULL) {
            // Failed to create a context for a passive client, so let's dispose the context of an active client and
            //   then try the next pair of styles
            jbNewXimClient_destroyInputContext(&activeClientIc);
        } else {
            // Both contexts have been created successfully
            break;
        }
    }

    if ((activeClientIc.xic == NULL) || (passiveClientIc.xic == NULL)) {
        jio_fprintf(stderr, "%s: failed to create an input context for an active client and/or a passive client. %u pairs of input styles have been tried\n",
                    __func__, inputStylesToTry.pairsCount);

        // If one of the contexts is NULL then both of them are expected to be NULL
        DASSERT(activeClientIc.xic == NULL);
        DASSERT(passiveClientIc.xic == NULL);

        goto finally;
    }

    pX11IMData->current_ic = NULL;
    pX11IMData->ic_active = activeClientIc;
    pX11IMData->ic_passive = passiveClientIc;

    pX11IMData->brokenImDetectionContext.isBetweenPreeditStartAndPreeditDone = False;

    /* Add the global reference object to X11InputMethod to the list. */
    addToX11InputMethodGRefList(pX11IMData->x11inputmethod);

    result = True;

finally:
    if (supportedXimInputStyles != NULL) {
        XFree(supportedXimInputStyles);
        supportedXimInputStyles = NULL;
    }

    if (result != True) {
        jbNewXimClient_destroyInputContext(&activeClientIc);
        jbNewXimClient_destroyInputContext(&passiveClientIc);
    }

    return result;
}


static jbNewXimClient_XIMFeatures jbNewXimClient_obtainSupportedXIMFeaturesBy(XIM inputMethod)
{
    jbNewXimClient_XIMFeatures result = { 0 };
    XIMValuesList *ximValues = NULL;
    XIMValuesList *xicValues = NULL;
    char *unsupportedIMValue = NULL;

    result.ximFeatures.isXNVisiblePositionAvailable = False;
    result.ximFeatures.isXNR6PreeditCallbackAvailable = False;

    result.xicFeatures.isXNStringConversionAvailable = False;
    result.xicFeatures.isXNStringConversionCallbackAvailable = False;
    result.xicFeatures.isXNResetStateAvailable = False;
    result.xicFeatures.isXNHotKeyAvailable = False;
    result.xicFeatures.isXNPreeditStateAvailable = False;
    result.xicFeatures.isXNPreeditStateNotifyCallbackAvailable = False;
    result.xicFeatures.isXNCommitStringCallbackAvailable = False;

    if (inputMethod == NULL) {
        return result;
    }

    unsupportedIMValue = XGetIMValues(
        inputMethod,
        XNQueryIMValuesList, &ximValues,
        XNQueryICValuesList, &xicValues,
        NULL
    );
    if (unsupportedIMValue != NULL) {
        jio_fprintf(stderr, "%s: failed to get the following property \"%s\".\n", __func__, unsupportedIMValue);
        // Mustn't be freed
        unsupportedIMValue = NULL;
    }

    if (ximValues != NULL) {
        for (unsigned int i = 0; i < ximValues->count_values; ++i) {
            if        (strcmp(XNVisiblePosition, ximValues->supported_values[i]) == 0) {
                result.ximFeatures.isXNVisiblePositionAvailable = True;
            } else if (strcmp(XNR6PreeditCallback, ximValues->supported_values[i]) == 0) {
                result.ximFeatures.isXNR6PreeditCallbackAvailable = True;
            }
        }
    }
    if (xicValues != NULL) {
        for (unsigned int i = 0; i < xicValues->count_values; ++i) {
            if        (strcmp(XNStringConversion, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNStringConversionAvailable = True;
            } else if (strcmp(XNStringConversionCallback, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNStringConversionCallbackAvailable = True;
            } else if (strcmp(XNResetState, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNResetStateAvailable = True;
            } else if (strcmp(XNHotKey, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNHotKeyAvailable = True;
            } else if (strcmp(XNPreeditState, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNPreeditStateAvailable = True;
            } else if (strcmp(XNPreeditStateNotifyCallback, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNPreeditStateNotifyCallbackAvailable = True;
            } else if (strcmp(XNCommitStringCallback, xicValues->supported_values[i]) == 0) {
                result.xicFeatures.isXNCommitStringCallbackAvailable = True;
            }
        }
    }

    if (ximValues != NULL) {
        XFree(ximValues);
        ximValues = NULL;
    }
    if (xicValues != NULL) {
        XFree(xicValues);
        xicValues = NULL;
    }

    return result;
}


static XIMStyles* jbNewXimClient_obtainSupportedInputStylesBy(XIM inputMethod)
{
    XIMStyles* result = NULL;
    char *unsupportedIMValue = NULL;

    if (inputMethod == NULL) {
        return NULL;
    }
    if ( (unsupportedIMValue = XGetIMValues(inputMethod, XNQueryInputStyle, &result, NULL)) != NULL ) {
        jio_fprintf(stderr, "%s: failed to get the following property \"%s\".\n", __func__, unsupportedIMValue);
        unsupportedIMValue = NULL;
    }

    return result;
}

static jbNewXimClient_PrioritizedStyles jbNewXimClient_chooseAndPrioritizeInputStyles(
    Bool preferBelowTheSpot,
    const XIMStyles *allXimSupportedInputStyles,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
) {
    jbNewXimClient_PrioritizedStyles result = { 0 };

    const jbNewXimClient_SupportedInputStyle activeClientStylesTemplate[] = {
        (preferBelowTheSpot == True) ? JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_BELOW_THE_SPOT_1 : JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_1,
        (preferBelowTheSpot == True) ?    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_1 : JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_2,
        (preferBelowTheSpot == True) ?    JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_2 : JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_BELOW_THE_SPOT_1,
        JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ROOT_WINDOW_1,
        JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_NOFEEDBACK
    };
    enum { ACTIVE_CLIENT_ALL_STYLES_COUNT = sizeof(activeClientStylesTemplate) / sizeof(activeClientStylesTemplate[0]) };

    const jbNewXimClient_SupportedInputStyle passiveClientStylesTemplate[] = {
        JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ROOT_WINDOW_1,
        JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_NOFEEDBACK
    };
    enum { PASSIVE_CLIENT_ALL_STYLES_COUNT = sizeof(passiveClientStylesTemplate) / sizeof(passiveClientStylesTemplate[0]) };

    // Will be filled later based on the templates
    jbNewXimClient_SupportedInputStyle activeClientStyles[ACTIVE_CLIENT_ALL_STYLES_COUNT] = { 0 };
    unsigned int activeClientStylesCount = 0;

    // Will be filled later based on the templates
    jbNewXimClient_SupportedInputStyle passiveClientStyles[PASSIVE_CLIENT_ALL_STYLES_COUNT] = { 0 };
    unsigned int passiveClientStylesCount = 0;

    unsigned int i = 0, j = 0;

    DASSERT(activeClientStylesCount  <= JBNEWXIMCLIENT_COUNTOF_SUPPORTED_INPUT_STYLES);
    DASSERT(passiveClientStylesCount <= JBNEWXIMCLIENT_COUNTOF_SUPPORTED_INPUT_STYLES);

    result.pairsCount = 0;

    if ((allXimSupportedInputStyles == NULL) || (allXimSupportedFeatures == NULL)) {
        return result;
    }

    // Filling activeClientStyles
    for (i = 0; i < ACTIVE_CLIENT_ALL_STYLES_COUNT; ++i) {
        const jbNewXimClient_SupportedInputStyle searchedStyle = activeClientStylesTemplate[i];

        for (j = 0; j < allXimSupportedInputStyles->count_styles; ++j) {
            if ( (allXimSupportedInputStyles->supported_styles[j] & searchedStyle) == searchedStyle ) {
                activeClientStyles[activeClientStylesCount++] = searchedStyle;
                break;
            }
        }
    }

    // Filling passiveClientStyles
    for (i = 0; i < PASSIVE_CLIENT_ALL_STYLES_COUNT; ++i) {
        const jbNewXimClient_SupportedInputStyle searchedStyle = passiveClientStylesTemplate[i];

        for (j = 0; j < allXimSupportedInputStyles->count_styles; ++j) {
            if ( (allXimSupportedInputStyles->supported_styles[j] & searchedStyle) == searchedStyle ) {
                passiveClientStyles[passiveClientStylesCount++] = searchedStyle;
                break;
            }
        }
    }

    // Combining the pairs (activeClientStyles[i], passiveClientStyles[j]) into result
    DASSERT(activeClientStylesCount * passiveClientStylesCount <= sizeof(result.combinations) / sizeof(result.combinations[0]));
    for (i = 0; i < activeClientStylesCount; ++i) {
        const jbNewXimClient_SupportedInputStyle activeStyle = activeClientStyles[i];

        for (j = 0; j < passiveClientStylesCount; ++j) {
            const jbNewXimClient_SupportedInputStyle passiveStyle = passiveClientStyles[i];

            result.combinations[result.pairsCount].forActiveClient  = activeStyle;
            result.combinations[result.pairsCount].forPassiveClient = passiveStyle;
            ++result.pairsCount;
        }
    }

    return result;
}


/**
 * Creates an input context of the style XIMPreeditPosition | XIMStatusNothing (corresponds to the java below-the-spot style).
 */
static jbNewXimClient_ExtendedInputContext jbNewXimClient_createInputContextOfPreeditPositionStatusNothing(
    JNIEnv *jEnv,
    jobject x11inputmethod,
    XIM xInputMethodConnection,
    Window window,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
);

/**
 * Creates an input context of the style XIMPreeditNothing | XIMStatusNothing (corresponds to the java root-window style).
 */
static jbNewXimClient_ExtendedInputContext jbNewXimClient_createInputContextOfPreeditNothingStatusNothing(
    JNIEnv *jEnv,
    jobject x11inputmethod,
    XIM xInputMethodConnection,
    Window window,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
);

static jbNewXimClient_ExtendedInputContext jbNewXimClient_createInputContextOfStyle(
    const jbNewXimClient_SupportedInputStyle style,
    JNIEnv * const jEnv,
    const X11InputMethodData * const pX11IMData,
    const XIM xInputMethodConnection,
    const Window window,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
) {
    jbNewXimClient_ExtendedInputContext result;
    jbNewXimClient_setInputContextFields(&result, NULL, 0, NULL, NULL, NULL, NULL);

    switch (style) {
        case JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_BELOW_THE_SPOT_1:
            result = jbNewXimClient_createInputContextOfPreeditPositionStatusNothing(
                jEnv, pX11IMData->x11inputmethod, xInputMethodConnection, window, allXimSupportedFeatures
            );
            break;
        case JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ROOT_WINDOW_1:
            result = jbNewXimClient_createInputContextOfPreeditNothingStatusNothing(
                jEnv, pX11IMData->x11inputmethod, xInputMethodConnection, window, allXimSupportedFeatures
            );
            break;
        case JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_1:
        case JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_ON_THE_SPOT_2:
        case JBNEWXIMCLIENT_SUPPORTED_INPUT_STYLE_NOFEEDBACK:
            // TODO: support
            break;
    }

    if (result.xic != NULL) {
        /* Unsets focus to avoid unexpected IM on */
        setXICFocus(result.xic, False);
    }

    return result;
}


/** A wrapper around XCreateFontSet */
static XFontSet jbNewXimClient_createIcFontset(Display *display, const char *xlfdFontSet);


static jbNewXimClient_ExtendedInputContext jbNewXimClient_createInputContextOfPreeditPositionStatusNothing(
    JNIEnv * const jEnv,
    const jobject x11inputmethod,
    const XIM xInputMethodConnection,
    const Window window,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
) {
    jbNewXimClient_ExtendedInputContext result;
    Display *xicDisplay = NULL;
    // XNFontSet
    XFontSet preeditFontSet = NULL;
    // XNSpotLocation
    XPoint imCandidatesInitLocation = { 0, 0 };
    // XNPreeditAttributes
    XVaNestedList preeditAttributes = NULL;
    XIC xic = NULL;
    char *unsupportedIMValue = NULL;

    jbNewXimClient_setInputContextFields(&result, NULL, 0, NULL, NULL, NULL, NULL);

    if ((jEnv == NULL) || (x11inputmethod == NULL) || (xInputMethodConnection == NULL) || (allXimSupportedFeatures == NULL)) {
        return result;
    }

    xicDisplay = XDisplayOfIM(xInputMethodConnection);
    if (xicDisplay == NULL) {
        jio_fprintf(stderr, "%s: xicDisplay == NULL.\n", __func__);
        goto finally;
    }

    preeditFontSet = jbNewXimClient_createIcFontset(
        xicDisplay,
        // Literally any fonts
        "-*-*-*-*-*-*-*-*-*-*-*-*-*-*"
    );
    if (preeditFontSet == NULL) {
        goto finally;
    }

    preeditAttributes = XVaCreateNestedList(0,
        /*
         * Xlib mistakenly requires to set XNFontSet for the XIMPreeditPosition style (otherwise XCreateIC fails)
         *   due to its own bug there:
         *   https://github.com/mirror/libX11/blob/ff8706a5eae25b8bafce300527079f68a201d27f/modules/im/ximcp/imRm.c#L2011
         *   (it should have XIM_MODE_PRE_DEFAULT instead of XIM_MODE_PRE_CREATE)
         */
        XNFontSet, preeditFontSet,
        /*
         * Xlib mistakenly requires to set XNSpotLocation for the XIMPreeditPosition style
         *   at the creation time (otherwise XCreateIC fails) due to its own bug there:
         *   https://github.com/mirror/libX11/blob/ff8706a5eae25b8bafce300527079f68a201d27f/modules/im/ximcp/imRm.c#L1951
         *   (it should have XIM_MODE_PRE_DEFAULT instead of XIM_MODE_PRE_CREATE)
         */
        XNSpotLocation, &imCandidatesInitLocation,
        NULL
    );
    if (preeditAttributes == NULL) {
        jio_fprintf(stderr, "%s: preeditAttributes == NULL.\n", __func__);
        goto finally;
    }

    xic = XCreateIC(
        xInputMethodConnection,
        /*
         * Since we're forced to set XNSpotLocation at the creation time (see above),
         *   we have to set XNClientWindow before
         *   (otherwise we can get an undefined behavior according to the documentation of the XNSpotLocation)
         */
        XNClientWindow, window,
        XNInputStyle, (XIMStyle)(XIMPreeditPosition | XIMStatusNothing),
        XNPreeditAttributes, preeditAttributes,
        NULL
    );

    XFree(preeditAttributes);
    preeditAttributes = NULL;

    if (xic == NULL) {
        jio_fprintf(stderr, "%s: XCreateIC failed to create an input context.\n", __func__);
        goto finally;
    }

    // Setting up various XIC properties

    // First, obligatory properties

    // XNClientWindow has already been set at XCreateIC

    if ( (unsupportedIMValue = XSetICValues(xic, XNFocusWindow, window, NULL)) != NULL ) {
        jio_fprintf(stderr, "%s: failed to set the following property \"%s\".\n", __func__, unsupportedIMValue);
        unsupportedIMValue = NULL;
        // Not a critical error so let's proceed
    }

    // Optional properties

    /*
     * Use commit string call back if possible.
     * This will ensure the correct order of preedit text and commit text
     */
    if (allXimSupportedFeatures->xicFeatures.isXNCommitStringCallbackAvailable == True)
    {
        const char* setIcErr = NULL;
        XIMCallback cb;
        cb.client_data = (XPointer)x11inputmethod;
        cb.callback = (XIMProc)&CommitStringCallback;

        if ( (setIcErr = XSetICValues(xic, XNCommitStringCallback, &cb, NULL)) != NULL ) {
            jio_fprintf(stderr, "%s: failed to set the IC value \"%s\".\n", __func__, setIcErr);
        }
    }

    /*
     * The code sets the IC mode that the preedit state is not initialized
     * at XmbResetIC. This attribute can be set at XCreateIC. I separately
     * set the attribute to avoid the failure of XCreateIC at some platform
     * which does not support the attribute.
     */
    if (allXimSupportedFeatures->xicFeatures.isXNResetStateAvailable == True) {
        const char* setIcErr = XSetICValues(xic, XNResetState, XIMInitialState, NULL);
        if (setIcErr != NULL) {
            jio_fprintf(stderr, "%s: failed to set the IC value \"%s\".\n", __func__, setIcErr);
        }
    }

    result.xic = xic;
    result.inputStyle = XIMPreeditPosition | XIMStatusNothing;
    result.xicDisplay = xicDisplay;
    result.preeditCustomFontSet = preeditFontSet;

finally:
    if (preeditAttributes != NULL) {
        XFree(preeditAttributes);
        preeditAttributes = NULL;
    }
    if (result.xic == NULL) {
        // Cleanup if smth failed

        if (xic != NULL) {
            XDestroyIC(xic);
            xic = NULL;
        }
        if (preeditFontSet != NULL) {
            DASSERT(xicDisplay != NULL);
            XFreeFontSet(xicDisplay, preeditFontSet);
            preeditFontSet = NULL;
        }
    }

    return result;
}

static jbNewXimClient_ExtendedInputContext jbNewXimClient_createInputContextOfPreeditNothingStatusNothing(
    JNIEnv * const jEnv,
    const jobject x11inputmethod,
    const XIM xInputMethodConnection,
    const Window window,
    const jbNewXimClient_XIMFeatures *allXimSupportedFeatures
) {
    jbNewXimClient_ExtendedInputContext result;
    Display *xicDisplay = NULL;
    XIC xic = NULL;
    char *unsupportedIMValue = NULL;

    jbNewXimClient_setInputContextFields(&result, NULL, 0, NULL, NULL, NULL, NULL);

    if ((jEnv == NULL) || (x11inputmethod == NULL) || (xInputMethodConnection == NULL) || (allXimSupportedFeatures == NULL)) {
        return result;
    }

    xicDisplay = XDisplayOfIM(xInputMethodConnection);
    if (xicDisplay == NULL) {
        jio_fprintf(stderr, "%s: xicDisplay == NULL.\n", __func__);
        goto finally;
    }

    xic = XCreateIC(
        xInputMethodConnection,
        XNInputStyle, (XIMStyle)(XIMPreeditNothing | XIMStatusNothing),
        NULL
    );

    if (xic == NULL) {
        jio_fprintf(stderr, "%s: XCreateIC failed to create an input context.\n", __func__);
        goto finally;
    }

    // Setting up various XIC properties

    // First, obligatory properties

    if ( (unsupportedIMValue = XSetICValues(xic, XNClientWindow, window, NULL)) != NULL ) {
        jio_fprintf(stderr, "%s: failed to set the following property \"%s\".\n", __func__, unsupportedIMValue);
        unsupportedIMValue = NULL;
        // The X protocol requires to set the property once and only once and before any input is done using
        //   the input context. So a failure here is critical, we can't proceed
        goto finally;
    }

    if ( (unsupportedIMValue = XSetICValues(xic, XNFocusWindow, window, NULL)) != NULL ) {
        jio_fprintf(stderr, "%s: failed to set the following property \"%s\".\n", __func__, unsupportedIMValue);
        unsupportedIMValue = NULL;
        // Not a critical error so let's proceed
    }

    // Optional properties

    /*
     * Use commit string call back if possible.
     * This will ensure the correct order of preedit text and commit text.
     */
    if (allXimSupportedFeatures->xicFeatures.isXNCommitStringCallbackAvailable == True)
    {
        const char* setIcErr = NULL;
        XIMCallback cb;
        cb.client_data = (XPointer)x11inputmethod;
        cb.callback = (XIMProc)&CommitStringCallback;

        if ( (setIcErr = XSetICValues(xic, XNCommitStringCallback, &cb, NULL)) != NULL ) {
            jio_fprintf(stderr, "%s: failed to set the IC value \"%s\".\n", __func__, setIcErr);
        }
    }

    /*
     * The code sets the IC mode that the preedit state is not initialized
     * at XmbResetIC.  This attribute can be set at XCreateIC. I separately
     * set the attribute to avoid the failure of XCreateIC at some platform
     * which does not support the attribute.
     */
    if (allXimSupportedFeatures->xicFeatures.isXNResetStateAvailable == True) {
        const char* setIcErr = XSetICValues(xic, XNResetState, XIMInitialState, NULL);
        if (setIcErr != NULL) {
            jio_fprintf(stderr, "%s: failed to set the IC value \"%s\".\n", __func__, setIcErr);
        }
    }

    result.xic = xic;
    result.inputStyle = XIMPreeditNothing | XIMStatusNothing;
    result.xicDisplay = xicDisplay;

finally:
    return result;
}


static XFontSet jbNewXimClient_createIcFontset(Display * const display, const char * const xlfdFontSet) {
    XFontSet result = NULL;
    // Has to be freed via XFreeStringList
    char** missingCharsets = NULL;
    int missingCharsetsCount = 0;
    // Mustn't be freed
    char* defStringReturn = NULL;

    if ((display == NULL) || (xlfdFontSet == NULL)) {
        return result;
    }

    result = XCreateFontSet(display, xlfdFontSet, &missingCharsets, &missingCharsetsCount, &defStringReturn);

    if (missingCharsets != NULL) {
        XFreeStringList(missingCharsets);
        missingCharsets = NULL;
        missingCharsetsCount = 0;
    }

    return result;
}


static inline void jbNewXimClient_setInputContextFields(
    // Non-nullable
    jbNewXimClient_ExtendedInputContext * const context,
    // Nullable
    const XIC xic,
    XIMStyle inputStyle,
    // Nullable
    Display * const xicDisplay,
    // Nullable
    const XFontSet preeditCustomFontSet,
    // Nullable
    const XFontSet statusCustomFontSet,
    // Nullable
    XIMCallback (* const preeditAndStatusCallbacks)[NCALLBACKS]
) {
    if (context == NULL) {
        return;
    }

    context->xic = xic;
    context->inputStyle = inputStyle;
    context->xicDisplay = xicDisplay;
    context->preeditCustomFontSet = preeditCustomFontSet;
    context->statusCustomFontSet = statusCustomFontSet;
    context->preeditAndStatusCallbacks = preeditAndStatusCallbacks;
}

static void jbNewXimClient_destroyInputContext(jbNewXimClient_ExtendedInputContext *context)
{
    if (context == NULL) {
        return;
    }

    jbNewXimClient_ExtendedInputContext localContext = *context;
    jbNewXimClient_setInputContextFields(context, NULL, 0, NULL, NULL, NULL, NULL);

    if (localContext.xic != NULL) {
        XDestroyIC(localContext.xic);
    }
    if (localContext.preeditCustomFontSet != NULL) {
        DASSERT(localContext.xicDisplay != NULL);
        XFreeFontSet(localContext.xicDisplay, localContext.preeditCustomFontSet);
    }
    if ((localContext.statusCustomFontSet != NULL) && (localContext.statusCustomFontSet != localContext.preeditCustomFontSet)) {
        DASSERT(localContext.xicDisplay != NULL);
        XFreeFontSet(localContext.xicDisplay, localContext.statusCustomFontSet);
    }
    if (localContext.preeditAndStatusCallbacks != NULL) {
        free(localContext.preeditAndStatusCallbacks);
    }
}


static void jbNewXimClient_moveImCandidatesWindow(XIC ic, XPoint newLocation)
{
    XVaNestedList preeditAttributes = NULL;
    char *unsupportedIMValue = NULL;

    if (ic == NULL) {
        jio_fprintf(stderr, "%s: ic == NULL.\n", __func__);
        return;
    }

    preeditAttributes = XVaCreateNestedList(0, XNSpotLocation, &newLocation, NULL);
    if (preeditAttributes == NULL) {
        jio_fprintf(stderr, "%s: failed to create XVaNestedList.\n", __func__);
        return;
    }

    unsupportedIMValue = XSetICValues(ic, XNPreeditAttributes, preeditAttributes, NULL);

    XFree(preeditAttributes);
    preeditAttributes = NULL;

    if (unsupportedIMValue != NULL) {
        jio_fprintf(stderr, "%s: failed to set the following property \"%s\".\n", __func__, unsupportedIMValue);
    }
}
