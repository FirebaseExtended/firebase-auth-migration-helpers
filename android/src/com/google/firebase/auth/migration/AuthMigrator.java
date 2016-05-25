/**
 * Copyright Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.firebase.auth.migration;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * A helper utility that will exchange a token from the legacy Firebase SDK for a user in the new
 * Firebase SDK.
 */
public final class AuthMigrator {
    private static final String EXCHANGE_ENDPOINT_TEMPLATE = "https://auth.firebase.com/v2/%s/sessions";
    private static final String SHARED_PREFERENCES_KEY = "com.firebase.authentication.credentials";
    private static final WeakHashMap<FirebaseApp, AuthMigrator> instances = new WeakHashMap<>();

    /**
     * Gets an AuthMigrator instance.
     */
    public static AuthMigrator getInstance(FirebaseApp app) {
        synchronized (instances) {
            AuthMigrator instance = instances.get(app);
            if (instance == null) {
                instance = new AuthMigrator(app);
                instances.put(app, instance);
            }
            return instance;
        }
    }

    /**
     * Gets the AuthMigrator instance for the default FirebaseApp.
     */
    public static AuthMigrator getInstance() {
        return getInstance(FirebaseApp.getInstance());
    }

    private final FirebaseApp app;
    private final SharedPreferences sharedPreferences;
    private final String repositoryHost;
    private final URL exchangeEndpoint;
    private AuthMigrator(FirebaseApp app) {
        this.app = app;
        this.sharedPreferences = app.getApplicationContext()
                .getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        this.repositoryHost = Uri.parse(app.getOptions().getDatabaseUrl()).getHost();
        try {
            this.exchangeEndpoint = new URL(
                    String.format(EXCHANGE_ENDPOINT_TEMPLATE, repositoryHost.split("\\.")[0]));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Unable to migrate app", e);
        }
    }

    /**
     * Gets the app for this AuthMigrator instance.
     */
    public FirebaseApp getApp() {
        return app;
    }

    private String getDefaultPersistenceKey() {
        String name = app.getName();
        if (name.equals("[DEFAULT]")) {
            name = "default";
        }
        return name;
    }

    private String getSharedPreferencesKey(String persistenceKey) {
        return repositoryHost + "/" + persistenceKey;
    }

    /**
     * Gets the stored token from the legacy SDK for the given persistence key.
     */
    private String getLegacyToken(String persistenceKey) {
        String rawData = sharedPreferences.getString(getSharedPreferencesKey(persistenceKey), null);
        if (rawData == null) {
            return null;
        }
        try {
            JSONObject jsonData = new JSONObject(rawData);
            return jsonData.getString("token");
        } catch (JSONException e) {
            // Couldn't parse the token, so return null.
            return null;
        }
    }

    /**
     * Migrates a user token from the legacy Firebase SDK, making that user the current user
     * in the new Firebase SDK.  Uses the FirebaseApp's name (or 'default' for
     * the default app) as the persistence key.
     *
     * This works as follows:
     * <ol>
     *     <li>Looks up the legacy auth token.</li>
     *     <li>Sends the legacy token to a Firebase server to exchange it for a new auth token.</li>
     *     <li>Uses the new auth token to log in the user.</li>
     *     <li>Removes the legacy auth token from the device.</li>
     * </ol>
     *
     * If a user is already logged in with the new Firebase SDK, then the legacy auth token will be
     * removed, but the logged in user will not be affected.
     *
     * If the Firebase server determines that the legacy auth token is invalid, it will be removed
     * and the user will not be logged in.
     */
    public Task<AuthResult> migrate() {
        return migrate(getDefaultPersistenceKey());
    }

    /**
     * Migrates a user token from the legacy Firebase SDK, making that user the current user
     * in the new Firebase SDK.
     *
     * This works as follows:
     * <ol>
     *     <li>Looks up the legacy auth token.</li>
     *     <li>Sends the legacy token to a Firebase server to exchange it for a new auth token.</li>
     *     <li>Uses the new auth token to log in the user.</li>
     *     <li>Removes the legacy auth token from the device.</li>
     * </ol>
     *
     * If a user is already logged in with the new Firebase SDK, then the legacy auth token will be
     * removed, but the logged in user will not be affected.
     *
     * If the Firebase server determines that the legacy auth token is invalid, it will be removed
     * and the user will not be logged in.
     */
    public Task<AuthResult> migrate(final String persistenceKey) {
        FirebaseUser currentUser = FirebaseAuth.getInstance(app).getCurrentUser();
        if (currentUser != null) {
            // If there's already a current user, don't migrate and clear the legacy token.
            clearLegacyAuth(persistenceKey);
            return Tasks.forResult((AuthResult)new MigratorAuthResult(currentUser));
        }

        String legacyToken = getLegacyToken(persistenceKey);
        if (legacyToken == null) {
            // If there's no legacy token, just return null.
            return Tasks.forResult((AuthResult)new MigratorAuthResult(null));
        }

        // Otherwise, exchange the token.
        return exchangeToken(legacyToken)
                .continueWithTask(new Continuation<String, Task<AuthResult>>() {
            @Override
            public Task<AuthResult> then(@NonNull Task<String> task) throws Exception {
                if (task.getResult() == null) {
                    return Tasks.forResult(null);
                }
                return FirebaseAuth.getInstance(app).signInWithCustomToken(task.getResult());
            }
        }).continueWithTask(new Continuation<AuthResult, Task<AuthResult>>() {
            @Override
            public Task<AuthResult> then(@NonNull Task<AuthResult> task) throws Exception {
                if (!task.isSuccessful()) {
                    try {
                        throw task.getException();
                    } catch (FirebaseWebRequestException e) {
                        if (e.getHttpStatusCode() == 400 || e.getHttpStatusCode() == 403) {
                            // Permanent errors should clear the persistence key.
                            clearLegacyAuth(persistenceKey);
                        }
                        return task;
                    }
                }
                clearLegacyAuth(persistenceKey);
                return task;
            }
        });
    }

    /**
     * Checks whether an auth token from the legacy SDK exists.  Uses the FirebaseApp's name
     * (or 'default' for the default app) as the persistence key.
     */
    public boolean hasLegacyAuth() {
        return hasLegacyAuth(getDefaultPersistenceKey());
    }

    /**
     * Checks whether an auth token from the legacy SDK exists.
     */
    public boolean hasLegacyAuth(String persistenceKey) {
        return getLegacyToken(persistenceKey) != null;
    }

    /**
     * Clears the auth token from the legacy SDK.  Uses the FirebaseApp's name (or 'default' for
     * the default app) as the persistence key.
     */
    public void clearLegacyAuth() {
        clearLegacyAuth(getDefaultPersistenceKey());
    }

    /**
     * Clears the auth token from the legacy SDK.
     */
    public void clearLegacyAuth(String persistenceKey) {
        sharedPreferences.edit().remove(getSharedPreferencesKey(persistenceKey)).apply();
    }

    private Task<String> exchangeToken(final String legacyToken) {
        if (legacyToken == null) {
            return Tasks.forResult(null);
        }
        return Tasks.call(Executors.newCachedThreadPool(), new Callable<String>() {
            @Override
            public String call() throws Exception {
                JSONObject postBody = new JSONObject();
                postBody.put("token", legacyToken);
                HttpURLConnection connection =
                        (HttpURLConnection) exchangeEndpoint.openConnection();
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestMethod("POST");
                OutputStreamWriter osw = new OutputStreamWriter(connection.getOutputStream());
                try {
                    osw.write(postBody.toString());
                    osw.flush();
                } finally {
                    osw.close();
                }
                int responseCode = connection.getResponseCode();

                InputStream is;
                if (responseCode >= 400) {
                    is = connection.getErrorStream();
                } else {
                    is = connection.getInputStream();
                }
                try {
                    byte[] buffer = new byte[1024];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int numRead = 0;
                    while ((numRead = is.read(buffer)) >= 0) {
                        baos.write(buffer, 0, numRead);
                    }
                    JSONObject resultObject = new JSONObject(new String(baos.toByteArray()));
                    if (responseCode != 200) {
                        throw new FirebaseWebRequestException(
                                resultObject.getJSONObject("error").getString("message"),
                                responseCode);
                    }
                    return resultObject.getString("token");
                } finally {
                    is.close();
                }
            }
        });
    }

    private static class FirebaseWebRequestException extends FirebaseException {
        private final int httpStatusCode;
        public FirebaseWebRequestException(String message, int httpStatusCode) {
            this.httpStatusCode = httpStatusCode;
        }

        public int getHttpStatusCode() {
            return httpStatusCode;
        }
    }

    private static class MigratorAuthResult implements AuthResult {
        private final FirebaseUser user;
        public MigratorAuthResult(FirebaseUser user) {
            this.user = user;
        }

        @Override
        public FirebaseUser getUser() {
            return user;
        }
    }
}
