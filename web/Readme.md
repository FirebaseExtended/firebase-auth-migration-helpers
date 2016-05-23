Firebase Auth Migrator
======================

A JavaScript code sample for migrating logged in users from a legacy Firebase
SDK to the new Firebase SDK.

Pre-requisites
--------------
Before using this code, you must add the Firebase JavaScript SDK to your page.

Getting Started
---------------
1. Include `migrate.js` in your page.
2. After you call `firebase.initializeApp` to initialize the Firebase SDK,
   call `migrate` to log in any user who was previously logged in with the
   legacy SDK.
```javascript
firebase.authMigrator().migrate().then(function(user) {
  if (!user) {
    // No user was logged in.
    return;
  }
  // user is logged in.
}).catch(function(error) {
  // There was an error.
});
```
4. Whenever you log in a user, call `clearLegacyAuth`. This is a precaution
   to make sure that a user from a legacy SDK never overrides a newer log in.
```javascript
firebase.auth().onAuthStateChanged(function(user) {
  if (user) {
    firebase.authMigrator().clearLegacyAuth();
  });
});
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
