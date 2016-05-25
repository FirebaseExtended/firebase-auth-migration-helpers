//
//  Copyright (c) 2016 Google Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

#import "FIRAuthMigrator.h"

@import Firebase;
@import FirebaseAuth;

@implementation FIRAuthMigrator {
//The database hostname for the Firebase app, such as "myapp.firebaseio.com".
@private NSString *host;
// Your app name from the host, such as "myapp".
@private NSString *namespace;
}

/**
 * Creates a new FIRAuthMigrator for the default FIRApp.
 */
+ (instancetype _Nonnull)authMigrator {
  return [self authMigratorWithApp:[FIRApp defaultApp]];
}

/**
 * Creates a new FIRAuthMigrator for the given FIRApp.
 */
+ (instancetype _Nonnull)authMigratorWithApp:(FIRApp * _Nonnull)app {
  return [[FIRAuthMigrator alloc] initWithApp:app];
}

/**
 * Initializes a new FIRAuthMigrator for the given FIRApp.
 */
- (instancetype _Nonnull)initWithApp:(FIRApp * _Nonnull)app {
  if (self = [super init]) {
    // Get the host and namespace for this app.
    NSURL *databaseURL = [NSURL URLWithString:app.options.databaseURL];
    self->host = databaseURL.host;
    NSArray *parts = [self->host componentsSeparatedByString:@"."];
    self->namespace = parts[0];
  }
  return self;
}

/**
 * Gets the legacy Firebase auth token out of the keychain.
 * @param error Out param for returning errors.
 * @return the legacy auth token, or nil if there is none, or if there's an error.
 */
- (NSString *)token:(NSError **)error {
  if (error) {
    *error = nil;
  }

  NSString *account = [NSString stringWithFormat:@"Firebase_%@", namespace];

  // Construct a keychain query to look up the legacy auth data.
  NSDictionary* query = @{ (__bridge NSString *)kSecClass:
                             (__bridge NSString *)kSecClassInternetPassword,
                           (__bridge NSString *)kSecAttrAccount: account,
                           (__bridge NSString *)kSecAttrServer: host,
                           (__bridge NSString *)kSecReturnData: (id)kCFBooleanTrue,
                           (__bridge NSString *)kSecReturnAttributes: (id)kCFBooleanTrue,
                           };

  CFDictionaryRef resultsRef = NULL;
  CFDictionaryRef queryRef = (__bridge_retained CFDictionaryRef)query;
  OSStatus status = SecItemCopyMatching(queryRef, (CFTypeRef *)&resultsRef);
  CFRelease(queryRef);
  NSDictionary* results = (__bridge_transfer NSDictionary *)resultsRef;

  if (status != noErr) {
    if (status == errSecItemNotFound) {
      // Return that the user wasn't logged in.
      return nil;
    }

    // Error checking looking up auth info in the keychain.
    if (error) {
      *error = [NSError errorWithDomain:(__bridge NSString *)kCFErrorDomainOSStatus
                                   code:status
                               userInfo:nil];
    }
    return nil;
  }

  // Pull out the auth data and deserialize the JSON dictionary.
  NSData* keyData = [results objectForKey:(__bridge NSString *)kSecValueData];
  NSError *err = nil;
  NSDictionary* keyDict = [NSJSONSerialization JSONObjectWithData:keyData
                                                          options:kNilOptions
                                                            error:&err];
  // Propagate any error in JSON deserialization to the caller.
  if (err) {
    if (error) {
      *error = err;
    }
    return nil;
  }

  // Get the "token" field. Ignore "authData" and "userData".
  NSString *token = keyDict[@"token"];
  return token;
}

/**
 * Removes the Firebase legacy auth token from the keychain, if present.
 */
- (void)clearLegacyAuth {
  NSString *account = [NSString stringWithFormat:@"Firebase_%@", namespace];

  // Construct a keychain query to look up the legacy auth data.
  NSDictionary* query = @{ (__bridge NSString *)kSecClass:
                             (__bridge NSString *)kSecClassInternetPassword,
                           (__bridge NSString *)kSecAttrAccount: account,
                           (__bridge NSString *)kSecAttrServer: host
                           };

  CFDictionaryRef queryRef = (__bridge_retained CFDictionaryRef)query;
  SecItemDelete(queryRef);
  CFRelease(queryRef);
}

/**
 * Takes a legacy Firebase auth token, sends it to Firebase's server to exchange it for a new token.
 * @param token The legacy Firebase auth token for the user.
 * @param callback Block to call upon completion.
 *                 newToken is the new token.
 *                 clearLegacyToken will be YES if exchange was successful or a permanent failure.
 */
- (void)exchangeToken:(NSString *)token
    completionHandler:(void (^ _Nonnull)(NSString *newToken,
                                         BOOL clearLegacyToken,
                                         NSError *error))callback {

  // Construct an http request to send to Firebase to exchange the legacy auth token for a new one.
  NSDictionary *jsonBody = @{ @"token": token };
  NSError *error = nil;
  NSData *body = [NSJSONSerialization dataWithJSONObject:jsonBody options:kNilOptions error:&error];
  if (error) {
    callback(nil, NO, error);
    return;
  }

  NSString *urlStr =
      [NSString stringWithFormat:@"https://auth.firebase.com/v2/%@/sessions", namespace];
  NSURL *url = [NSURL URLWithString:urlStr];
  NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
  req.HTTPMethod = @"POST";
  req.HTTPBody = body;
  [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];

  NSURLSession *session = [NSURLSession sharedSession];
  [[session dataTaskWithRequest:req
              completionHandler:^(NSData * _Nullable data,
                                  NSURLResponse * _Nullable response,
                                  NSError * _Nullable error) {
                if (error) {
                  callback(nil, NO, error);
                  return;
                }

                // Check whether we got a valid response from Firebase.
                NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *)response;
                if (httpResponse.statusCode != 200) {
                  error = [NSError errorWithDomain:FIRAuthErrorDomain
                                              code:FIRAuthErrorCodeInvalidUserToken
                                          userInfo:nil];
                  BOOL permanent = ((httpResponse.statusCode == 400) ||
                                    (httpResponse.statusCode == 403));
                  callback(nil, permanent, error);
                  return;
                }

                // Parse the JSON response of the request and propagate any errors.
                NSDictionary* jsonResponse = [NSJSONSerialization JSONObjectWithData:data
                                                                            options:kNilOptions
                                                                              error:&error];
                if (error) {
                  callback(nil, NO, error);
                  return;
                }

                // Pull the new token out of the response.
                NSString *token = jsonResponse[@"token"];
                if (token == nil) {
                  error = [NSError errorWithDomain:FIRAuthErrorDomain
                                              code:FIRAuthErrorCodeInvalidUserToken
                                         userInfo:nil];
                  callback(nil, NO, error);
                  return;
                }

                callback(token, YES, error);
              }] resume];
}

/**
 * Looks up whether a user is logged in with a legacy Firebase SDK and logs them in with the current
 * Firebase SDK. This works as follows:
 * 1. Looks up the legacy auth token in the keychain.
 * 2. Sends the legacy token to a Firebase server to exchange it for a new auth token.
 * 3. Uses the new auth token to log in the user.
 * 4. Removes the legacy auth token from the keychain.
 *
 * If a user is already logged in with the new Firebase SDK, then the legacy auth token will be
 * removed, but the logged in user will not be affected.
 *
 * If the Firebase server determines that the legacy auth token is invalid, it will be removed, and
 * the user will not be logged in.
 *
 * @param completion Callback for when the exchange is complete. user can be nil if either:
 * 1. There was a (temporary or permanent) failure, as indicated by error, OR
 * 2. There was no legacy auth token present.
 */
- (void)migrate:(void (^ _Nullable)(FIRUser * _Nullable user,
                                    NSError * _Nullable error))completion {
  // A simple helper to run the given callback on the main dispatch queue.
  void (^callback)(FIRUser * _Nullable user, NSError * _Nullable error) =
      ^void(FIRUser * _Nullable user, NSError * _Nullable error) {
        if (!completion) {
          return;
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          completion(user, error);
        });
      };

  // Check if there's already a Firebase user.
  FIRUser *currentUser = [[FIRAuth auth] currentUser];
  if (currentUser) {
    // Someone's already logged in, so clear the legacy token and keep using the current user.
    [self clearLegacyAuth];
    callback(currentUser, nil);
  }

  // Get the legacy token out of the keychain.
  NSError *error;
  NSString *token = [self token:&error];
  if (!token) {
    callback(nil, error);
    return;
  }

  // Send the legacy token to Firebase to exchange it for a new one.
  [self exchangeToken:token
    completionHandler:^(NSString *newToken, BOOL clearLegacyToken, NSError *error) {
      if (!newToken) {
        callback(nil, error);
        return;
      }

      // Pass the new token on to the standard Firebase sign in method.
      [[FIRAuth auth] signInWithCustomToken:newToken
                                 completion:^(FIRUser * _Nullable user, NSError * _Nullable error) {
                                   if (!error && clearLegacyToken) {
                                     [self clearLegacyAuth];
                                   }
                                   callback(user, error);
                                 }];
  }];
}

/**
 * Returns whether a user is logged in on this device with a legacy Firebase SDK.
 */
- (BOOL)hasLegacyAuth:(NSError**)error {
  NSError *internalError;
  NSString *token = [self token:&internalError];
  if (internalError) {
    if (error) {
      *error = internalError;
    }
    return NO;
  }
  return !!token;
}

@end
