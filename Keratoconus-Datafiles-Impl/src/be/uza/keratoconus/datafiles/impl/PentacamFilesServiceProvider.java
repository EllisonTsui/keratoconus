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

package be.uza.keratoconus.datafiles.impl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.log.LogService;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.component.Reference;
import be.uza.keratoconus.datafiles.api.PentacamFile;
import be.uza.keratoconus.datafiles.api.PentacamFilesService;
import be.uza.keratoconus.model.api.ModelFileService;

/**
 * This component provides the PentacamFilesService. However it will only
 * register this service once it has successfully created an instance of
 * <code>PentacamCsvFile</code> for every file which is needed; hence the empty
 * &lsquo;provide&rsquo; list in the @Component annotation.
 * 
 * @author Chris Gray
 *
 */
@Component(immediate = true, provide = {})
public class PentacamFilesServiceProvider implements PentacamFilesService {

	private static final String BE_UZA_KERATOCONUS_DATAFILES_IMPL_PENTACAM_CSV_FILE = PentacamCsvFile.class
			.getName();

	private Map<String, PentacamFile> files = new ConcurrentHashMap<String, PentacamFile>();
	private Lock filesReadyLock = new ReentrantLock();
	private Condition filesReadyCondition = filesReadyLock.newCondition();
	private boolean allFilesPresent;
	private List<String> fileBaseNames;
	private TimeUnit timeoutUnit = TimeUnit.SECONDS;
	private long timeout = 300L;
	private List<PentacamFile> allFiles = new ArrayList<PentacamFile>();
	private BundleContext bundleContext;
	private LogService logService;
	private AtomicReference<Thread> serviceRegistrationThreadRef = new AtomicReference<>();
	private AtomicReference<ServiceRegistration> registrationRef = new AtomicReference<>();
	private ModelFileService modelFileService;
	private ConfigurationAdmin configurationAdmin;
	private List<Configuration> componentConfigurations;

	@Reference
	protected void setConfigurationAdmin(ConfigurationAdmin ca) {
		configurationAdmin = ca;
	}

	@Reference
	protected void setLogService(LogService logService) {
		this.logService = logService;
	}

	@Reference
	protected void setModelFileService(ModelFileService mfs) {
		this.modelFileService = mfs;
	}

	@Reference(dynamic = true, multiple = true, optional = true)
	protected void addPentacamFile(PentacamFile pf) {
		files.put(pf.getBaseName(), pf);
		checkAllFilesPresent();
	}

	protected void removePentacamFile(PentacamFile pf) {
		files.remove(pf.getBaseName());
	}

	@Activate
	protected void activate(ComponentContext cc) throws IOException {
		bundleContext = cc.getBundleContext();
		componentConfigurations = new CopyOnWriteArrayList<Configuration>();
		Thread thread = new Thread(() -> {
			createPentacamFileConfigurations();
			registerServiceWhenAllFilesArePresent();
		});
		serviceRegistrationThreadRef.set(thread);
		thread.start();
	}

	@Deactivate
	protected void deactivate() throws IOException {
		Thread thread = serviceRegistrationThreadRef.getAndSet(null);
		if (thread != null) {
			thread.interrupt();
		}
		ServiceRegistration registration = registrationRef.getAndSet(null);
		if (registration != null) {
			registration.unregister();
		}
		destroyComponentConfigurations();
	}

	private void createPentacamFileConfigurations() {
		fileBaseNames = new ArrayList<>();
		
		for (String fbn : modelFileService.getFileBaseNames()) {
			fileBaseNames.add(fbn);
			fileBaseNames.add(fbn + "-LOAD");
			String separator = modelFileService.getSeparatorForFile(fbn);
			List<String> fields = modelFileService.getFieldsOfFile(fbn);
			createComponentConfiguration(
					BE_UZA_KERATOCONUS_DATAFILES_IMPL_PENTACAM_CSV_FILE, fbn,
					separator, fields);
			createComponentConfiguration(
					BE_UZA_KERATOCONUS_DATAFILES_IMPL_PENTACAM_CSV_FILE, fbn
							+ "-LOAD", separator, fields);
		}
	}

	private void createComponentConfiguration(String factoryPid,
			String fileBaseName, String separator, List<String> fields) {
		try {
			Configuration c;
			c = configurationAdmin.createFactoryConfiguration(factoryPid, null);
			Dictionary<String, Object> dict = new Hashtable<>();
			dict.put("pentacam.file.name", fileBaseName);
			dict.put("pentacam.field.separator", separator);
			dict.put("pentacam.fields", fields);
			logService.log(LogService.LOG_INFO,
					BE_UZA_KERATOCONUS_DATAFILES_IMPL_PENTACAM_CSV_FILE
							+ " config: " + dict);
			c.update(dict);
			componentConfigurations.add(c);
		} catch (IOException e) {
			logService.log(LogService.LOG_ERROR,
					"Failed to launch PentacamCsvFile configuration for "
							+ fileBaseName);
		}
	}

	private void destroyComponentConfigurations() throws IOException {
		for (Configuration c : componentConfigurations) {
			c.delete();
		}
	}
	
	private void registerServiceWhenAllFilesArePresent() {
		checkAllFilesPresent();
		filesReadyLock.lock();
		Date deadline = new Date(System.currentTimeMillis()
				+ timeoutUnit.toMillis(timeout));
		boolean ready = true;
		try {
			while (!allFilesPresent) {
				ready |= filesReadyCondition.await(1L, TimeUnit.SECONDS);
				if (!ready && new Date().after(deadline)) {
					throw new TimeoutException(
							"Timeout when waiting for datafiles to be read.");
				}
			}
			for (String baseName : fileBaseNames) {
				final PentacamFile pf = files.get(baseName);
				allFiles.add(pf);
			}
		} catch (InterruptedException | TimeoutException e) {
			logService.log(LogService.LOG_ERROR,
					"Failed to start all PentacamFile service instances", e);
		} finally {
			filesReadyLock.unlock();
		}

		// Now we register the service
		final Hashtable<String, Object> properties = new Hashtable<String, Object>();
		// TODO does this serve any useful purpose?
		properties.put("files", allFiles);
		registrationRef.set(bundleContext.registerService(
				PentacamFilesService.class.getName(), this, properties));
	}

	@Override
	public List<PentacamFile> getAllFiles() throws FileNotFoundException {
		return allFiles;
	}

	@Override
	public PentacamFile getFileByBaseName(String baseName) {
		return files.get(baseName);
	}

	private void checkAllFilesPresent() {
		filesReadyLock.lock();
		try {
			Set<String> availableFileBaseNames = files.keySet();
			if (fileBaseNames == null) {
				logService
						.log(LogService.LOG_INFO,
								"Not ready (PentacamFilesService is not yet activated)");
				return;
			}

			HashSet<String> neededSet = new HashSet<>(fileBaseNames);
			neededSet.removeAll(availableFileBaseNames);
			logService.log(LogService.LOG_INFO, "Have files "
					+ availableFileBaseNames + ", still need " + neededSet);
			if (neededSet.isEmpty()) {
				allFilesPresent = true;
				filesReadyCondition.signalAll();
			}
		} finally {
			filesReadyLock.unlock();
		}
	}

}
