/*
 * Copyright (c) 2011, 2018, Oracle and/or its affiliates. All rights reserved.
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

#import <sys/stat.h>
#import <Cocoa/Cocoa.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "CFileDialog.h"
#import "AWTWindow.h"
#import "CMenuBar.h"
#import "ApplicationDelegate.h"

#import "java_awt_FileDialog.h"
#import "sun_lwawt_macosx_CFileDialog.h"

@implementation CFileDialog

- (id)initWithOwner:(NSWindow*)owner
              filter:(jboolean)inHasFilter
           fileTypes:(NSArray *)inFileTypes
          fileDialog:(jobject)inDialog
               title:(NSString *)inTitle
           directory:(NSString *)inPath
                file:(NSString *)inFile
                mode:(jint)inMode
            modality:(jint)inModality
        multipleMode:(BOOL)inMultipleMode
      shouldNavigate:(BOOL)inNavigateApps
canChooseDirectories:(BOOL)inChooseDirectories
      canChooseFiles:(BOOL)inChooseFiles
canCreateDirectories:(BOOL)inCreateDirectories
             withEnv:(JNIEnv*)env;
{
    if (self = [super init]) {
        fOwner = owner;
        [fOwner retain];
        fHasFileFilter = inHasFilter;
        fFileTypes = inFileTypes;
        [fFileTypes retain];
        fFileDialog = (*env)->NewGlobalRef(env, inDialog);
        fDirectory = inPath;
        [fDirectory retain];
        fFile = inFile;
        [fFile retain];
        fTitle = inTitle;
        [fTitle retain];
        fMode = inMode;
        fModality = inModality;
        fMultipleMode = inMultipleMode;
        fNavigateApps = inNavigateApps;
        fChooseDirectories = inChooseDirectories;
        fChooseFiles = inChooseFiles;
        fCreateDirectories = inCreateDirectories;
        fPanelResult = NSModalResponseContinue;
        fCondition = [[NSCondition alloc] init];
    }

    return self;
}

-(void) disposer {
    if (fFileDialog != NULL) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        (*env)->DeleteGlobalRef(env, fFileDialog);
        fFileDialog = NULL;
    }
}

-(void) dealloc {
    [fFileTypes release];
    fFileTypes = nil;

    [fDirectory release];
    fDirectory = nil;

    [fFile release];
    fFile = nil;

    [fTitle release];
    fTitle = nil;

    [fURLs release];
    fURLs = nil;

    [fOwner release];
    fOwner = nil;

    [fCondition release];
    fCondition = nil;

    [super dealloc];
}

- (void)safeSaveOrLoad {
    NSSavePanel *thePanel = nil;

    /*
     * 8013553: turns off extension hiding for the native file dialog.
     * This way is used because setExtensionHidden(NO) doesn't work
     * as expected.
     */
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    [defaults setBool:NO forKey:@"NSNavLastUserSetHideExtensionButtonState"];

    if (fMode == java_awt_FileDialog_SAVE) {
        thePanel = [NSSavePanel savePanel];
        [thePanel setAllowsOtherFileTypes:YES];
    } else {
        thePanel = [NSOpenPanel openPanel];
    }

    if (thePanel != nil) {
        [thePanel setTitle:fTitle];

        if (fFileTypes != nil) {
            if (@available(macOS 11, *)) {
                int nTypes = (int)[fFileTypes count];
                NSMutableArray *contentTypes = [NSMutableArray arrayWithCapacity:nTypes];
                for (int i = 0; i < nTypes; ++i) {
                    NSString *fileType = (NSString *)[fFileTypes objectAtIndex:i];
                    UTType *contentType = [UTType typeWithFilenameExtension:fileType conformingToType:UTTypeData];
                    if (contentType != nil) {
                        [contentTypes addObject:contentType];
                    } else if (fileType.length == 0) {
                        [contentTypes addObject:UTTypeUnixExecutable];
                    }
                }
                [thePanel setAllowedContentTypes:contentTypes];
            } else {
                [thePanel setAllowedFileTypes:fFileTypes];
            }
        }

        if (fNavigateApps) {
            [thePanel setTreatsFilePackagesAsDirectories:YES];
        }

        if (fMode == java_awt_FileDialog_LOAD) {
            NSOpenPanel *openPanel = (NSOpenPanel *)thePanel;
            [openPanel setAllowsMultipleSelection:fMultipleMode];
            [openPanel setCanChooseFiles:fChooseFiles];
            [openPanel setCanChooseDirectories:fChooseDirectories];
            [openPanel setCanCreateDirectories:fCreateDirectories];
        }

        [thePanel setDelegate:self];

        NSMenuItem *editMenuItem = nil;
        NSMenu *menu = [NSApp mainMenu];
        if (menu != nil) {
            if (menu.numberOfItems > 0) {
                NSMenu *submenu = [[menu itemAtIndex:0] submenu];
                if (submenu != nil) {
                    menu = submenu;
                }
            }

            editMenuItem = [self createEditMenu];
            if (menu.numberOfItems > 0) {
                [menu insertItem:editMenuItem atIndex:0];
            } else {
                [menu addItem:editMenuItem];
            }
            [editMenuItem release];
        }

        if (fDirectory != nil) {
            [thePanel setDirectoryURL:[NSURL fileURLWithPath:[fDirectory stringByExpandingTildeInPath]]];
        }
        if (fFile != nil) {
            [thePanel setNameFieldStringValue:fFile];
        }

        AWTWindow *awtWindow = nil;
        CMenuBar *menuBar = nil;
        if (fOwner != nil) {
            // Finds appropriate menubar in our hierarchy,
            awtWindow = (AWTWindow *)fOwner.delegate;
            while (awtWindow.ownerWindow != nil) {
                awtWindow = awtWindow.ownerWindow;
            }

            BOOL isDisabled = NO;
            if ([awtWindow.nsWindow isVisible]){
                menuBar = awtWindow.javaMenuBar;
                isDisabled = !awtWindow.isEnabled;
            }

            if (menuBar != nil) {
                [CMenuBar activate:menuBar modallyDisabled:isDisabled];
            }

            if (@available(macOS 10.14, *)) {
                [thePanel setAppearance:fOwner.appearance];
            }
        }

        void (^completionHandler)(NSModalResponse) = ^(NSModalResponse result) {
            [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
                if (menuBar != nil) {
                    [CMenuBar activate:menuBar modallyDisabled:NO];
                }

                if (editMenuItem != nil) {
                    [menu removeItem:editMenuItem];
                }

                if (result == NSModalResponseOK) {
                    if (fMode == java_awt_FileDialog_LOAD) {
                        NSOpenPanel *openPanel = (NSOpenPanel *)thePanel;
                        fURLs = [openPanel URLs];
                    } else {
                        fURLs = [NSArray arrayWithObject:[thePanel URL]];
                    }
                    [fURLs retain];
                }

                [thePanel setDelegate:nil];
                [self disposer];

                [fCondition lock];
                fPanelResult = result;
                [fCondition signal];
                [fCondition unlock];
            }];
        };

        if (fModality == 0) {
            [thePanel beginWithCompletionHandler:completionHandler];
        } else if (fModality == 1) {
            [thePanel beginSheetModalForWindow:awtWindow.nsWindow completionHandler:completionHandler];
        } else {
            completionHandler([thePanel runModal]);
        }

    } else {
        [self disposer];
    }
}

- (NSMenuItem *) createEditMenu {
    NSMenu *editMenu = [[NSMenu alloc] initWithTitle:@"Edit"];

    NSMenuItem *cutItem = [[NSMenuItem alloc] initWithTitle:@"Cut" action:@selector(cut:) keyEquivalent:@"x"];
    [editMenu addItem:cutItem];
    [cutItem release];

    NSMenuItem *copyItem = [[NSMenuItem alloc] initWithTitle:@"Copy" action:@selector(copy:) keyEquivalent:@"c"];
    [editMenu addItem:copyItem];
    [copyItem release];

    NSMenuItem *pasteItem = [[NSMenuItem alloc] initWithTitle:@"Paste" action:@selector(paste:) keyEquivalent:@"v"];
    [editMenu addItem:pasteItem];
    [pasteItem release];

    NSMenuItem *selectAllItem = [[NSMenuItem alloc] initWithTitle:@"Select All" action:@selector(selectAll:) keyEquivalent:@"a"];
    [editMenu addItem:selectAllItem];
    [selectAllItem release];

    NSMenuItem *editMenuItem = [NSMenuItem new];
    [editMenuItem setTitle:@"Edit"];
    [editMenuItem setSubmenu:editMenu];
    [editMenu release];

    return editMenuItem;
}

- (BOOL) askFilenameFilter:(NSString *)filename {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jstring jString = NormalizedPathJavaStringFromNSString(env, filename);

    DECLARE_CLASS_RETURN(jc_CFileDialog, "sun/lwawt/macosx/CFileDialog", NO);
    DECLARE_METHOD_RETURN(jm_queryFF, jc_CFileDialog, "queryFilenameFilter", "(Ljava/lang/String;)Z", NO);
    BOOL returnValue = (*env)->CallBooleanMethod(env, fFileDialog, jm_queryFF, jString);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, jString);

    return returnValue;
}

- (BOOL)panel:(id)sender shouldEnableURL:(NSURL *)url {
    if (!fHasFileFilter) return YES; // no filter, no problem!

    // check if it's not a normal file
    NSNumber *isFile = nil;
    if ([url getResourceValue:&isFile forKey:NSURLIsRegularFileKey error:nil]) {
        if (![isFile boolValue]) return YES; // always show directories and non-file entities (browsing servers/mounts, etc)
    }

    // if in directory-browsing mode, don't offer files
    if ((fMode != java_awt_FileDialog_LOAD) && (fMode != java_awt_FileDialog_SAVE)) {
        return NO;
    }

    // ask the file filter up in Java
    NSString* filePath = (NSString*)CFURLCopyFileSystemPath((CFURLRef)url, kCFURLPOSIXPathStyle);
    BOOL shouldEnableFile = [self askFilenameFilter:filePath];
    [filePath release];
    return shouldEnableFile;
}

- (NSModalResponse) wait {
    [fCondition lock];
    while (fPanelResult == NSModalResponseContinue) {
        [fCondition wait];
    }
    [fCondition unlock];
    return fPanelResult;
}

- (NSArray *)URLs {
    return [[fURLs retain] autorelease];
}
@end

/*
 * Class:     sun_lwawt_macosx_CFileDialog
 * Method:    nativeRunFileDialog
 */
JNIEXPORT jobjectArray JNICALL
Java_sun_lwawt_macosx_CFileDialog_nativeRunFileDialog
(JNIEnv *env, jobject peer, jlong ownerPtr, jstring title, jint mode, jint modality, jboolean multipleMode,
 jboolean navigateApps, jboolean chooseDirectories, jboolean chooseFiles, jboolean createDirectories,
 jboolean hasFilter, jobjectArray allowedFileTypes, jstring directory, jstring file)
{
    jobjectArray returnValue = NULL;

JNI_COCOA_ENTER(env);
    NSString *dialogTitle = JavaStringToNSString(env, title);
    if ([dialogTitle length] == 0) {
        dialogTitle = @" ";
    }

    NSMutableArray *fileTypes = nil;
    if (allowedFileTypes != nil) {
        int nTypes = (*env)->GetArrayLength(env, allowedFileTypes);
        if (nTypes > 0) {
            fileTypes = [NSMutableArray arrayWithCapacity:nTypes];
            for (int i = 0; i < nTypes; i++) {
                jstring fileType = (jstring)(*env)->GetObjectArrayElement(env, allowedFileTypes, i);
                [fileTypes addObject:JavaStringToNSString(env, fileType)];
                (*env)->DeleteLocalRef(env, fileType);
            }
        }
    }

    CFileDialog *dialogDelegate = [[CFileDialog alloc] initWithOwner:(NSWindow *)jlong_to_ptr(ownerPtr)
                                                               filter:hasFilter
                                                            fileTypes:fileTypes
                                                           fileDialog:peer
                                                                title:dialogTitle
                                                            directory:JavaStringToNSString(env, directory)
                                                                 file:JavaStringToNSString(env, file)
                                                                 mode:mode
                                                             modality:modality
                                                         multipleMode:multipleMode
                                                       shouldNavigate:navigateApps
                                                 canChooseDirectories:chooseDirectories
                                                       canChooseFiles:chooseFiles
                                                 canCreateDirectories:createDirectories
                                                              withEnv:env];

    [ThreadUtilities performOnMainThread:@selector(safeSaveOrLoad)
                                 on:dialogDelegate
                         withObject:nil
                      waitUntilDone:YES];

    if ([dialogDelegate wait] == NSModalResponseOK) {
        NSArray *urls = [dialogDelegate URLs];
        jsize count = [urls count];

        DECLARE_CLASS_RETURN(jc_String, "java/lang/String", NULL);
        returnValue = (*env)->NewObjectArray(env, count, jc_String, NULL);

        [urls enumerateObjectsUsingBlock:^(id url, NSUInteger index, BOOL *stop) {
            jstring filename = NormalizedPathJavaStringFromNSString(env, [url path]);
            (*env)->SetObjectArrayElement(env, returnValue, index, filename);
            (*env)->DeleteLocalRef(env, filename);
        }];
    }

    [dialogDelegate release];
JNI_COCOA_EXIT(env);
    return returnValue;
}
