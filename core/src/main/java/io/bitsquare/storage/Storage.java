/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.storage;

import io.bitsquare.gui.components.Popups;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.Serializable;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * That class handles the storage of a particular object to disk using Java serialisation.
 * To support evolving versions of the serialised data we need to take care that we don't break the object structure.
 * Java serialisation is tolerant with added fields, but removing or changing existing fields will break the backwards compatibility.
 * Alternative frameworks for serialisation like Kyro or mapDB have shown problems with version migration, so we stuck with plain Java
 * serialisation.
 * <p/>
 * For every data object we write a separate file to minimize the risk of corrupted files in case of inconsistency from newer versions.
 * In case of a corrupted file we backup the old file to a separate directory, so if it holds critical data it might be helpful for recovery.
 * <p/>
 * We also backup at first read the file, so we have a valid file form the latest version in case a write operation corrupted the file.
 * <p/>
 * The read operation is triggered just at object creation (startup) and is at the moment not executed on a background thread to avoid asynchronous behaviour.
 * As the data are small and it is just one read access the performance penalty is small and might be even worse to create and setup a thread for it.
 * <p/>
 * The write operation used a background thread and supports a delayed write to avoid too many repeated write operations.
 */
public class Storage<T extends Serializable> {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    public static final String DIR_KEY = "storage.dir";

    private final File dir;
    private FileManager<T> fileManager;
    private File storageFile;
    private T serializable;
    private String fileName;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public Storage(@Named(DIR_KEY) File dir) {
        this.dir = dir;
    }

    public T initAndGetPersisted(T serializable) {
        return initAndGetPersisted(serializable, serializable.getClass().getSimpleName());
    }

    public T initAndGetPersisted(T serializable, String fileName) {
        this.serializable = serializable;
        this.fileName = fileName;
        storageFile = new File(dir, fileName);
        fileManager = new FileManager<>(dir, storageFile, 500, TimeUnit.MILLISECONDS);

        return getPersisted(serializable);
    }

    // Save delayed and on a background thread
    public void save() {
        if (storageFile == null)
            throw new RuntimeException("storageFile = null. Call setupFileStorage before using read/write.");

        fileManager.saveLater(serializable);
    }

    public void remove(String fileName) {
        fileManager.removeFile(fileName);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We do the file read on the UI thread to avoid problems from multi threading. 
    // Data are small and read is done only at startup, so it is no performance issue.
    private T getPersisted(T serializable) {
        if (storageFile == null)
            throw new RuntimeException("storageFile = null. Call init before using read/write.");

        try {
            long now = System.currentTimeMillis();
            T persistedObject = (T) fileManager.read(storageFile);
            log.info("Read {} completed in {}msec", serializable.getClass().getSimpleName(), System.currentTimeMillis() - now);

            // If we did not get any exception we can be sure the data are consistent so we make a backup 
            now = System.currentTimeMillis();
            fileManager.backupFile(fileName);
            log.info("Backup {} completed in {}msec", serializable.getClass().getSimpleName(), System.currentTimeMillis() - now);

            return persistedObject;
        } catch (InvalidClassException e) {
            log.error("Version of persisted class has changed. We cannot read the persisted data anymore. We make a backup and remove the inconsistent file.");
            try {
                // In case the persisted data have been critical (keys) we keep a backup which might be used for recovery
                fileManager.removeAndBackupFile(fileName);
            } catch (IOException e1) {
                e1.printStackTrace();
                log.error(e1.getMessage());
                // We swallow Exception if backup fails
            }
        } catch (FileNotFoundException e) {
            log.info("File not available. That is OK for the first run.");
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            log.error(e.getMessage());
            Popups.openErrorPopup("An exception occurred at reading data from disc.", e.getMessage());

        }
        return null;
    }
}
