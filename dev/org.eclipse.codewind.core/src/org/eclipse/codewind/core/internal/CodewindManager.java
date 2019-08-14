/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.codewind.core.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeoutException;

import org.eclipse.codewind.core.internal.InstallUtil.InstallStatus;
import org.eclipse.codewind.core.internal.connection.CodewindConnection;
import org.eclipse.codewind.core.internal.connection.CodewindConnectionManager;
import org.json.JSONException;
import org.json.JSONObject;

public class CodewindManager {
	
	private static CodewindManager codewindManager;
	
	CodewindConnection localConnection = null;
	URI localURI = null;
	String version = null;
	
	// Keep track of the install status and if the installer is currently running.
	// If the installer is running, this is the status that should be reported, if
	// not the install status should be reported (installerStatus will be null).
	InstallStatus installStatus = null;
	InstallerStatus installerStatus = null;
	
	public enum InstallerStatus {
		INSTALLING,
		UNINSTALLING,
		STARTING,
		STOPPING
	};
	
	private CodewindManager() {
		if (getInstallStatus(true) == InstallStatus.RUNNING) {
			createLocalConnection();
			if (localConnection != null) {
				localConnection.refreshApps(null);
			}
		}
	}

	public static CodewindManager getManager() {
		if (codewindManager == null) {
			codewindManager = new CodewindManager();
		}
		return codewindManager;
	}
	
	/**
	 * Get the current install status for Codewind
	 */
	public InstallStatus getInstallStatus(boolean update) {
		if (installStatus != null && !update) {
			return installStatus;
		}
		String url = null;
		try {
			JSONObject statusObj = InstallUtil.getRawInstallStatus();
			installStatus = InstallStatus.getStatus(statusObj.getString(InstallUtil.STATUS_KEY));
			if (installStatus == InstallStatus.RUNNING) {
				url = statusObj.getString(InstallUtil.URL_KEY);
				if (!url.endsWith("/")) {
					url = url + "/";
				}
				localURI = new URI(url);
			} else {
				removeLocalConnection();
				localURI = null;
				version = null;
			}
			return installStatus;
		} catch (IOException e) {
			Logger.logError("An error occurred trying to get the installer status", e); //$NON-NLS-1$
		} catch (TimeoutException e) {
			Logger.logError("Timed out trying to get the installer status", e); //$NON-NLS-1$
		} catch (JSONException e) {
			Logger.logError("The Codewind installer status format is not recognized", e); //$NON-NLS-1$
		} catch (URISyntaxException e) {
			Logger.logError("The Codewind installer status command returned an invalid url: " + url, e);
		}
		return InstallStatus.UNKNOWN;
	}
	
	public InstallerStatus getInstallerStatus() {
		return installerStatus;
	}
	
	public void setInstallerStatus(InstallerStatus status) {
		this.installerStatus = status;
		CoreUtil.updateAll();
	}
	
	public URI getLocalURI() {
		if (localURI == null) {
			getInstallStatus(true);
		}
		return localURI;
	}
	
	public String getVersion() {
		if (version == null && getInstallStatus(false) == InstallStatus.RUNNING) {
			version = CodewindConnection.getVersion(getLocalURI());
		}
		return version;
	}
	
	public boolean isSupportedVersion(String version) {
		return CodewindConnection.isSupportedVersion(version);
	}
	
	public synchronized CodewindConnection getLocalConnection() {
		return localConnection;
	}

	public synchronized CodewindConnection createLocalConnection() {
		if (localConnection != null) {
			return localConnection;
		}
		try {
			String version = getVersion();
			if (version != null && isSupportedVersion(version)) {
				CodewindConnection connection = CodewindObjectFactory.createCodewindConnection(getLocalURI());
				localConnection = connection;
				CodewindConnectionManager.add(connection);
				return connection;
			}
		} catch(Exception e) {
			Logger.log("Attempting to connect to Codewind failed: " + e.getMessage()); //$NON-NLS-1$
		}
		return null;
	}
	
	public synchronized void removeLocalConnection() {
		if (localConnection != null) {
			localConnection.close();
			CodewindConnectionManager.removeConnection(localConnection.baseUrl.toString());
			localConnection = null;
		}
	}
	
	public void refresh() {
		for (CodewindConnection conn : CodewindConnectionManager.activeConnections()) {
			conn.refreshApps(null);
		}
	}
	
	public boolean hasActiveApplications() {
		for (CodewindConnection conn : CodewindConnectionManager.activeConnections()) {
			for (CodewindApplication app : conn.getApps()) {
				if (app.isAvailable()) {
					return true;
				}
			}
		}
		return false;
	}

}
