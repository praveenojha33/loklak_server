/**
 *  LoginServlet
 *  Copyright 27.05.2015 by Robert Mader, @treba13
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package org.loklak.api.cms;

import org.eclipse.jetty.util.log.Log;
import org.json.JSONObject;
import org.loklak.data.DAO;
import org.loklak.server.*;
import org.loklak.tools.IO;
import org.loklak.tools.storage.JSONObjectWithDefault;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.security.*;
import java.time.Instant;
import java.util.Base64;
import java.util.TreeMap;

/**
 * This service allows users to login, logout or to check their login status.
 * For login, there are three options: session, cookie (both stateful, for browsers) and access-token (stateless, for api access)
 * It requires the following parameters: login (the login id, usually an email), password and type (one of the above)
 * To check if the user is logged it, set the parameter 'checkLogin' to true
 * To logout, set the parameter 'logout' to true
 */
public class LoginService extends AbstractAPIHandler implements APIHandler {

	private static final long serialVersionUID = 8578478303032749879L;
	private static final long defaultAccessTokenExpireTime = 7 * 24 * 60 * 60;

	@Override
	public BaseUserRole getMinimalBaseUserRole() {
		return BaseUserRole.ANONYMOUS;
	}

	@Override
	public JSONObject getDefaultPermissions(BaseUserRole baseUserRole) {
		JSONObject result = new JSONObject();
		result.put("maxInvalidLogins", 10);
		result.put("blockTimeSeconds", 20);
		return result;
	}

	public String getAPIPath() {
		return "/api/login.json";
	}

	@Override
	public JSONObject serviceImpl(Query post, HttpServletResponse response, Authorization authorization, final JSONObjectWithDefault permissions)
			throws APIException {

		// login check for app
		if(post.get("checkLogin", false)) {
			JSONObject result = new JSONObject();
			if (authorization.getIdentity().isEmail()) {
				result.put("loggedIn", true);
				result.put("message", "You are logged in as " + authorization.getIdentity().getName());
			}
			else{
				result.put("loggedIn", false);
				result.put("message", "Not logged in");
			}
			return result;
		}

		// do logout if requested
		if(post.get("logout", false)){	// logout if requested

			// invalidate session
			post.getRequest().getSession().invalidate();

			// delete cookie if set
			deleteLoginCookie(response);

			JSONObject result = new JSONObject();
			result.put("message", "Logout successful");
			return result;
		}

		// check login type by checking which parameters are set
		boolean passwordLogin = false;
		boolean pubkeyHello = false;
		boolean pubkeyLogin = false;

		if(post.get("login", null) != null && post.get("password", null) != null && post.get("type", null ) != null){
			passwordLogin = true;
		}
		else if(post.get("login", null) != null && post.get("keyhash", null) != null){
			pubkeyHello = true;
		}
		else if(post.get("sessionID", null) != null && post.get("response", null) != null){
			pubkeyLogin = true;
		}
		else{
			throw new APIException(400, "Bad login parameters.");
		}

		// check if too many invalid login attempts were made already
		JSONObject invalidLogins = authorization.getAccounting().getRequests(this.getClass().getCanonicalName());
		/*Long lastKey = invalidLogins.floorKey(System.currentTimeMillis() + 1000);
		if(invalidLogins.size() > permissions.getInt("maxInvalidLogins", 10)
				&& lastKey > System.currentTimeMillis() - permissions.getInt("blockTimeSeconds", 120) * 1000){
			throw new APIException(403, "Too many invalid login attempts. Try again in "
					+ (permissions.getInt("blockTimeSeconds", 120) * 1000 - System.currentTimeMillis() + lastKey) / 1000
					+ " seconds");
		}*/

		if(passwordLogin) { // do login via password

			String login = post.get("login", null);
			String password = post.get("password", null);
			String type = post.get("type", null);

			Authentication authentication = getAuthentication(new ClientCredential(ClientCredential.Type.passwd_login, login), post);
			ClientIdentity identity = authentication.getIdentity();

			// check if the password is valid
			String passwordHash;
			String salt;
			try {
				passwordHash = authentication.getString("passwordHash");
				salt = authentication.getString("salt");
			} catch (Throwable e) {
				Log.getLog().info("Invalid login try for user: " + identity.getName() + " from host: " + post.getClientHost() + " : password or salt missing in database");
				throw new APIException(422, "Invalid credentials");
			}

			if (!passwordHash.equals(getHash(password, salt))) {

				// save invalid login in accounting object
				authorization.getAccounting().addRequest(this.getClass().getCanonicalName(), "invalid login");

				Log.getLog().info("Invalid login try for user: " + identity.getName() + " via passwd from host: " + post.getClientHost());
				throw new APIException(422, "Invalid credentials");
			}

			JSONObject result = new JSONObject();

			switch (type) {
				case "session": // create a browser session
					post.getRequest().getSession().setAttribute("identity", identity);
					break;
				case "cookie": // set a long living cookie
					// create random string as token
					String loginToken = createRandomString(30);

					// create cookie
					Cookie loginCookie = new Cookie("login", loginToken);
					loginCookie.setPath("/");
					loginCookie.setMaxAge(defaultCookieTime.intValue());

					// write cookie to database
					ClientCredential cookieCredential = new ClientCredential(ClientCredential.Type.cookie, loginToken);
					JSONObject user_obj = new JSONObject();
					user_obj.put("id", identity.toString());
					user_obj.put("expires_on", Instant.now().getEpochSecond() + defaultCookieTime);
					DAO.authentication.put(cookieCredential.toString(), user_obj, cookieCredential.isPersistent());

					response.addCookie(loginCookie);
					break;
				case "access-token": // create and display an access token

					long valid_seconds;
					try {
						valid_seconds = post.get("valid_seconds", defaultAccessTokenExpireTime);
					} catch (Throwable e) {
						throw new APIException(400, "Invalid value for 'valid_seconds'");
					}

					String token = createAccessToken(identity, valid_seconds);

					if(valid_seconds == -1) result.put("valid_seconds", "forever");
					else result.put("valid_seconds", valid_seconds);

					result.put("access_token", token);

					break;
				default:
					throw new APIException(400, "Invalid type");
			}

			Log.getLog().info("login for user: " + identity.getName() + " via passwd from host: " + post.getClientHost());

			result.put("message", "You are logged in as " + identity.getName());
			return result;
		}
		else if(pubkeyHello){ // first part of pubkey login: if the key hash is known, create a challenge

			String login = post.get("login", null);
			String keyHash = post.get("keyhash", null);

			Authentication authentication = getAuthentication(new ClientCredential(ClientCredential.Type.passwd_login, login), post);
			ClientIdentity identity = authentication.getIdentity();

			if(!DAO.login_keys.has(identity.toString()) || !DAO.login_keys.getJSONObject(identity.toString()).has(keyHash)) throw new APIException(400, "Unknown key");

			String challengeString = createRandomString(30);
			String newSessionID = createRandomString(30);

			ClientCredential credential = new ClientCredential(ClientCredential.Type.pubkey_challange, newSessionID);
			Authentication challenge_auth = new Authentication(credential, DAO.authentication);
			challenge_auth.setIdentity(identity);
			challenge_auth.put("activated", true);

			challenge_auth.put("challenge", challengeString);
			challenge_auth.put("key", DAO.login_keys.getJSONObject(identity.toString()).getString(keyHash));
			challenge_auth.setExpireTime(60 * 10);

			JSONObject result = new JSONObject();
			result.put("challenge", challengeString);
			result.put("sessionID", newSessionID);
			result.put("message", "Found valid key for this user. Sign the challenge with you public key and send it back, together with the sessionID");
			return result;
		}
		else if(pubkeyLogin){ // second part of pubkey login: verify if the response to the challange is valid

			String sessionID = post.get("sessionID", null);
			String challangeResponse = post.get("response", null);

			Authentication authentication = getAuthentication(new ClientCredential(ClientCredential.Type.pubkey_challange, sessionID), post);
			ClientIdentity identity = authentication.getIdentity();

			String challenge = authentication.getString("challenge");
			PublicKey key = IO.decodePublicKey(authentication.getString("key"), "RSA");

			Signature sig;
			boolean verified;
			try {
				sig = Signature.getInstance("SHA256withRSA");
				sig.initVerify(key);
				sig.update(challenge.getBytes());
				verified = sig.verify(Base64.getDecoder().decode(challangeResponse));
			} catch (NoSuchAlgorithmException e){
				throw new APIException(400, "No such algorithm");
			} catch (InvalidKeyException e){
				throw new APIException(400, "Invalid key");
			} catch (SignatureException e){
				throw new APIException(400, "Bad signature");
			} catch (Throwable e){
				throw new APIException(400, "Bad signature");
			}

			if(verified){
				long valid_seconds;
				try {
					valid_seconds = post.get("valid_seconds", defaultAccessTokenExpireTime);
				} catch (Throwable e) {
					throw new APIException(400, "Invalid value for 'valid_seconds'");
				}

				String token = createAccessToken(identity, valid_seconds);

				JSONObject result = new JSONObject();

				if(valid_seconds == -1) result.put("valid_seconds", "forever");
				else result.put("valid_seconds", valid_seconds);

				result.put("access_token", token);
				return result;
			}
			else {
				throw new APIException(400, "Bad Signature");
			}
		}
		throw new APIException(500, "Server error");
	}

	private Authentication getAuthentication(ClientCredential credential, Query post) throws APIException{
		// create Authentication
		Authentication authentication = new Authentication(credential, DAO.authentication);

		if (authentication.getIdentity() == null) { // check if identity is valid
			authentication.delete();
			Log.getLog().info("Invalid login try for unknown user: " + credential.getName() + " via passwd from host: " + post.getClientHost());
			throw new APIException(422, "Invalid credentials");
		}

		if (!authentication.getBoolean("activated", false)) { // check if identity is valid
			Log.getLog().info("Invalid login try for user: " + credential.getName() + " from host: " + post.getClientHost() + " : user not activated yet");
			throw new APIException(422, "User not yet activated");
		}

		return authentication;
	}

	private String createAccessToken(ClientIdentity identity, long valid_seconds) throws APIException{
		String token = createRandomString(30);

		ClientCredential accessToken = new ClientCredential(ClientCredential.Type.access_token, token);
		Authentication tokenAuthentication = new Authentication(accessToken, DAO.authentication);

		tokenAuthentication.setIdentity(identity);

		if (valid_seconds == 0 || valid_seconds < -1) { // invalid values
			throw new APIException(400, "Invalid value for 'valid_seconds'");
		} else if (valid_seconds != -1){
			tokenAuthentication.setExpireTime(valid_seconds);
		}

		return token;
	}
}