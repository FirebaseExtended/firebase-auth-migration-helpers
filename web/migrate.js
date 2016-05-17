/**
 * Creates a new FirebaseAuthMigrator with the given app.
 */
var FirebaseAuthMigrator = function(app) {
  var databaseURL = app.options.databaseURL;
  var url = new URL(databaseURL);
  this.namespace = url.host.split('.')[0];
  this.storageKey = "firebase:session::" + this.namespace;
  return this;
};

/**
 * Creates a new FirebaseAuthMigrator with the given app.
 */
firebase.app.prototype.authMigrator = function() {
  return new FirebaseAuthMigrator(this);
};

/**
 * Creates a new FirebaseAuthMigrator with the default app.
 */
firebase.authMigrator = function() {
  return new FirebaseAuthMigrator(this.app());
};

/**
 * Returns the legacy Firebase auth token, if present.
 */
FirebaseAuthMigrator.prototype.getLegacyToken = function() {
  var authDataJSON = localStorage.getItem(this.storageKey);
  if (!authDataJSON) {
    return null;
  }

  try {
    var authData = JSON.parse(authDataJSON)
    return authData.token;
  } catch (e) {
    // Shouldn't happen, but if the data's invalid, there's not much we can do.
    this.clearLegacyAuth();
    return null;
  }
};

/**
 * Returns whether there is a legacy Firebase auth token present.
 */
FirebaseAuthMigrator.prototype.hasLegacyAuth = function() {
  return !!this.getLegacyToken();
};

/**
 * Removes any legacy Firebase auth token.
 */
FirebaseAuthMigrator.prototype.clearLegacyAuth = function() {
  localStorage.removeItem(this.storageKey);
};

/**
 * Sends a legacy auth token to a Firebase backend and receives a new token.
 */
FirebaseAuthMigrator.prototype.exchangeToken = function(token) {
  var migrator = this;
  var url = "https://auth.firebase.com/v2/" + this.namespace + "/sessions";
  return new firebase.Promise(function(resolve, reject) {
    var req = new XMLHttpRequest();
    req.responseType = "json";
    req.onreadystatechange = function() {
      if (req.readyState !== req.DONE) {
        return;
      }
      if (req.status !== 200) {
        if (req.status === 400 || req.status === 403) {
          // This is permanently failed. Clear the old token.
          migrator.clearLegacyAuth();
        }
        reject(new firebase.auth.Error("invalid-user-token",
            "Invalid auth token."));
        return;
      }
      var newToken = req.response.token;
      if (!newToken) {
        reject(new firebase.auth.Error("network-request-failed",
            "Invalid response from Firebase."));
        return;
      }
      resolve(newToken);
    };
    req.onerror = function() {
      reject(new firebase.auth.Error("network-request-failed",
          "Unable to verify auth token."));

    };
    req.open("POST", url, true);
    req.setRequestHeader("Content-Type", "application/json");
    req.send(JSON.stringify({ token: token }));
  });
};

/**
 * Looks up whether a user is logged in with a legacy Firebase SDK and logs them
 * in with the current Firebase SDK. This works as follows:
 * 1. Looks up the legacy auth token in the keychain.
 * 2. Sends the legacy token to a server to exchange it for a new auth token.
 * 3. Uses the new auth token to log in the user.
 * 4. Removes the legacy auth token from the keychain.
 *
 * If a user is already logged in with the new Firebase SDK, then the legacy
 * auth token will be removed, but the logged in user will not be affected.
 *
 * If the Firebase server determines that the legacy auth token is invalid, it
 * will be removed, and the user will not be logged in.
 *
 * Returns a Promise containing the user, or null if there was none logged in.
 */
FirebaseAuthMigrator.prototype.migrate = function() {
  if (firebase.auth().currentUser) {
    // There's already a user logged in with the new SDK.
    this.clearLegacyAuth();
    return Promise.resolve(firebase.auth().currentUser);
  }

  var token = this.getLegacyToken();
  if (!token) {
    // No user was logged in.
    return Promise.resolve(null);
  }

  var migrator = this;
  return this.exchangeToken(token).then(function(newToken) {
    return firebase.auth().signInWithCustomToken(newToken).then(function(user) {
      migrator.clearLegacyAuth();
      return user;
    });
  });
};
