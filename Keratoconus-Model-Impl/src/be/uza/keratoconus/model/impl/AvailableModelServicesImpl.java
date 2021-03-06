/*
    This file is part of Keratoconus Assistant.

    Keratoconus Assistant is free software: you can redistribute it 
    and/or modify it under the terms of the GNU General Public License 
    as published by the Free Software Foundation, either version 3 of 
    the License, or (at your option) any later version.

    Keratoconus Assistant is distributed in the hope that it will be 
    useful, but WITHOUT ANY WARRANTY; without even the implied warranty 
    of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Keratoconus Assistant.  If not, see 
    <http://www.gnu.org/licenses/>.
 */

package be.uza.keratoconus.model.impl;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.log.LogService;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.ConfigurationPolicy;
import aQute.bnd.annotation.component.Reference;
import be.uza.keratoconus.model.api.AvailableModelsService;
import be.uza.keratoconus.model.api.ModelService;

@Component(configurationPolicy = ConfigurationPolicy.ignore)
public class AvailableModelServicesImpl implements AvailableModelsService {

	private static final String CONFIG_PATH_PREFIX = "/config/";
	private static final String CONFIG_PATH_SUFFIX = ".properties";
	private static final String MODEL_PATH_PREFIX = "/model/";
	private static final String MODEL_PATH_SUFFIX = ".model";
//	private static final String COMMA = ",";

	private Map<String, String> modelNamesAndDescriptions;
	private LogService logService;
	private String selectedModelName;
	private ConfigurationAdmin configurationAdmin;

	@Reference
	protected void setConfigurationAdmin(ConfigurationAdmin ca) {
		configurationAdmin = ca;
	}


	@Reference
	protected void setLogService(LogService ls) {
		logService = ls;
	}

	@Activate
	protected void activate(BundleContext bc) throws IOException {
		modelNamesAndDescriptions = locateModelNames(bc);
		if (modelNamesAndDescriptions.size() == 0) {
			throw new RuntimeException("No classification models were found.");
		}
	}

	private Map<String, String> locateModelNames(BundleContext bc) throws IOException {
		final Map<String, String> modelNames = new HashMap<>();
		final Map<String, String> configNames = new HashMap<>();
		final Bundle myBundle = bc.getBundle();
		@SuppressWarnings("unchecked")
		Enumeration<URL> modelEntries = myBundle.findEntries(MODEL_PATH_PREFIX,
				"*" + MODEL_PATH_SUFFIX, false);
		@SuppressWarnings("unchecked")
		Enumeration<URL> configEntries = myBundle.findEntries(
				CONFIG_PATH_PREFIX, "*" + CONFIG_PATH_SUFFIX, false);
		while (configEntries.hasMoreElements()) {
			final URL nextElement = configEntries.nextElement();
			final String configName = path2name(nextElement.getPath(),
					CONFIG_PATH_PREFIX, CONFIG_PATH_SUFFIX);
			final URLConnection connection = nextElement.openConnection();
			final Properties configProperties = new Properties();
			configProperties.load(connection.getInputStream());
			configNames.put(configName,
					configProperties.getProperty("model.description"));
		}
		while (modelEntries.hasMoreElements()) {
			final String modelName = path2name(modelEntries.nextElement()
					.getPath(), MODEL_PATH_PREFIX, MODEL_PATH_SUFFIX);
			String description = configNames.remove(modelName);
			if (description != null) {
				logService.log(LogService.LOG_INFO,
						"Found model and config file for: " + modelName
								+ ", description: " + description);
				modelNames.put(modelName, description);
			} else {
				logService.log(LogService.LOG_WARNING,
						"Found model file with no corresponding config file, ignoring: "
								+ modelName);
			}
		}
		for (String configName : configNames.keySet()) {
			logService.log(LogService.LOG_WARNING,
					"Found config file with no corresponding model file, ignoring: "
							+ configName);
		}
		return modelNames;
	}

	private String path2name(final String path, String prefix, String suffix) {
		return path.substring(prefix.length(), path.length() - suffix.length());
	}

	@Override
	public List<String> getAvailableModelNames() {
		return new ArrayList<>(modelNamesAndDescriptions.keySet());
	}

	@Override
	public void selectModel(String modelName) throws Exception {
		logService.log(LogService.LOG_INFO, "Selecting model: " + modelName);
		try {
			String factoryPid = ModelServiceImpl.class.getName();
			Configuration c = configurationAdmin.createFactoryConfiguration(factoryPid, null);
			Dictionary<String, Object> dict = new Hashtable<>();
			dict.put(ModelService.MODEL_NAME, modelName);
			logService.log(LogService.LOG_INFO,
					"Creating configuration for " + factoryPid
							+ " : " + dict);
			c.update(dict);
			selectedModelName = modelName;
		} catch (IOException e) {
			logService.log(LogService.LOG_ERROR,
					"Failed to launch ModelService configuration for "
							+ modelName);
		}
	}
	

	@Override
	public String getSelectedModelName() {
		return selectedModelName;
	}

	@Override
	public String getModelDescription(String name) {
		return modelNamesAndDescriptions.get(name);
	}

}
