FIRAuthMigrator
===============

An Objective-C code sample for migrating logged in users from a legacy Firebase
iOS SDK to the new Firebase SDK.

Pre-requisites
--------------
Before using this code, you must add the Firebase/Auth modules to your project.

Getting Started
---------------
1. Add the FIRAuthMigrator.h and FIRAuthMigrator.m to your project.
2. If you are using Swift, add `#import "FIRAuthMigrator.h"` to your Bridging
   Header file. If you do not have a Bridging Header file, create one:
    1. Create a new file call `{Project}-Bridging-Header.h`.
        1. File > New > File...
        2. iOS > Source > Header File
        3. Enter the file name and click `Create`.
    2.  In your project's `Build Settings`, click `All`, and enter the path to
        your briding header file for `Objective-C Bridging Header`.
3. In your AppDelegate's `application:didFinishLaunchingWithOptions:` method,
   call `migrateAuth:` to log in any user who was previously logged in with the
   legacy SDK.
```objective-c
// Objective C
[[FIRAuthMigrator authMigrator] migrate:^(FIRUser *user, NSError *error) {
  if (error != nil) {
    // There was an error.
    return;
  }
  if (user == nil) {
    // No user was logged in.
    return;
  }
  // user is logged in
}];
```
```swift
// Swift
FIRAuthMigrator.authMigrator().migrate() { (user, error) in
  if error != nil {
    // There was an error.
    return
  }
  if user == nil {
    // No user was logged in.
    return
  }
  // user is logged in
}
```
4. Whenever you log in a user, call `clearLegacyAuth`. This is a precaution
   to make sure that a user from a legacy SDK never overrides a newer log in.
```objective-c
// Objective C
[[FIRAuthMigrator authMigrator] clearLegacyAuth];
```
```swift
// Swift
FIRAuthMigrator.authMigrator().clearLegacyAuth()
```

Support
-------
If you've found an error in this sample, please file an issue:
https://github.com/firebase/firebase-auth-migration-helpers/issues

Patches are encouraged, and may be submitted by forking this project and
submitting a pull request through GitHub.

License
-------

Copyright 2016 Google, Inc.

Licensed to the Apache Software Foundation (ASF) under one or more contributor
license agreements.  See the NOTICE file distributed with this work for
additional information regarding copyright ownership.  The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License.  You may obtain a copy of
the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.
