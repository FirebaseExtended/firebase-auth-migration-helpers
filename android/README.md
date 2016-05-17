Firebase Auth Migrator
======================

An Android code sample for migrating logged in users from a legacy Firebase
Android SDK to the new Firebase SDK.

Pre-requisites
--------------
Before using this code, you must add the Firebase/Auth modules to your project.

Getting Started
---------------
1. Add the `AuthMigrator.java` to your project under
   `src/com/google/firebase/auth/migration`.
3. In your Application's or main Activity's `onCreate()` override,
   call `migrate()` to log in any user who was previously logged in with the
   legacy SDK.
```java
AuthMigrator.getInstance().migrate()
  .continueWith(new Continuation<AuthResult, Void>() {
    @Override
    public Void then(@NonNull Task<AuthResult> task) throws Exception {
      if (task.isSuccessful()) {
        if (task.getUser() != null) {
          // Either your existing user remains logged in or a FirebaseUser
          // was created from your legacy Auth state.
        } else {
          // There was no legacy auth user.
        }
      } else {
        // An error occurred.
      }
      return null;
    }
  });
```
4. Whenever you log in a user, call `clearLegacyAuth()`. This is a precaution
   to make sure that a user from a legacy SDK never overrides a newer log in.
```java
AuthMigrator.getInstance().clearLegacyAuth();
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
