/*
 * Copyright (c) 2021.
 *
 * This file is part of DiscoAPI.
 *
 *     DiscoAPI is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 2 of the License, or
 *     (at your option) any later version.
 *
 *     DiscoAPI is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DiscoAPI.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.foojay.api;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import io.foojay.api.pkg.Pkg;
import io.foojay.api.util.Config;
import io.foojay.api.util.Constants;
import io.foojay.api.util.OutputFormat;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static io.foojay.api.pkg.Pkg.FIELD_FILENAME;
import static io.foojay.api.pkg.Pkg.FIELD_LATEST_BUILD_AVAILABLE;


public enum MongoDbManager {
    INSTANCE;

    private static final Logger        LOGGER           = LoggerFactory.getLogger(MongoDbManager.class);

    private static final String        FIELD_PACKAGE_ID = "id";
    private static final String        FIELD_DOWNLOADS  = "downloads";

    private              MongoClient   mongoClient;
    private              boolean       connected;
    private              MongoDatabase database;


    MongoDbManager() {
        connected = false;
    }


    public void init() {
        if (null == Config.INSTANCE.getFoojayMongoDbUser() ||
            null == Config.INSTANCE.getFoojayMongoDbPassword() ||
            null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            connected = false;
        } else {
            try {
                final MongoCredential credential = MongoCredential.createCredential(Config.INSTANCE.getFoojayMongoDbUser(),
                                                                                    Config.INSTANCE.getFoojayMongoDbDatabase(),
                                                                                    Config.INSTANCE.getFoojayMongoDbPassword().toCharArray());
                mongoClient = MongoClients.create(MongoClientSettings.builder()
                                                                     .applyToClusterSettings(builder -> builder.hosts(Arrays.asList(new ServerAddress(Config.INSTANCE.getFoojayMongoDbUrl(), Config.INSTANCE.getFoojayMongoDbPort()))))
                                                                     .credential(credential)
                                                                     .build());

                database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
                connected = true;
                LOGGER.debug("Established connection to mongodb at {}:{}", Config.INSTANCE.getFoojayMongoDbUrl(), Config.INSTANCE.getFoojayMongoDbPort());

                final MongoCollection pkgsCollection = database.getCollection(Constants.PACKAGES_COLLECTION);
                if (null == pkgsCollection) {
                    LOGGER.debug("Creating mongodb collection {}", Constants.PACKAGES_COLLECTION);
                    database.createCollection(Constants.PACKAGES_COLLECTION, null);
                }
                MongoCollection downloadsCollection = database.getCollection(Constants.DOWNLOADS_COLLECTION);
                if (null == downloadsCollection) {
                    LOGGER.debug("Creating mongodb collection {}", Constants.DOWNLOADS_COLLECTION);
                    database.createCollection(Constants.DOWNLOADS_COLLECTION, null);
                }
            } catch (MongoException e) {
                connected = false;
                LOGGER.debug("Error connecting to mongodb at {}:{}. {}", Config.INSTANCE.getFoojayMongoDbUrl(), Config.INSTANCE.getFoojayMongoDbPort(), e.getMessage());
            }
        }
    }

    public boolean isConnected() { return connected; }

    public void connect() {
        if (connected) { return; }
        init();
    }

    /**
     * Returns list of all packages in the packages collection
     * @return list of all packages in the packages collection
     */
    public List<Pkg> getPkgs() {
        if (!connected) { init(); }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Cannot return packages because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return new ArrayList<>();
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.BUNDLES_COLLECTION not set.");
            return new ArrayList<>();
        };
        final MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        final List<Pkg>                 result     = new ArrayList<>();
        collection.find().forEach(document -> {
            Pkg pkg = new Pkg(document.toJson());
            result.add(pkg);
        });
        LOGGER.debug("Successfully returned {} packages from mongodb.", result.size());
        return result;
    }

    /**
     * Inserts given list of packages to packages collection
     * @param pkgs
     */
    public void insertAllPkgs(final Collection<Pkg> pkgs) {
        if (!connected) { init(); }
        if (null == pkgs || pkgs.isEmpty()) {
            LOGGER.debug("Packages are null or empty.");
            return;
        }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Packages not inserted because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.BUNDLES_COLLECTION not set.");
            return;
        };
        final MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        final List<Document>            documents  = new ArrayList<>();
        for (Pkg pkg : pkgs) {
            try {
                documents.add(Document.parse(pkg.toString(OutputFormat.FULL_COMPRESSED)));
            } catch (JsonParseException e) {
                LOGGER.error("Error parsing json when adding package {}. {}", pkg.getId(), e.getMessage());
            }
        }
        collection.insertMany(documents);
        LOGGER.debug("Successfully inserted {} packages to mongodb.", pkgs.size());
    }

    /**
     * Adds the given list of packages to the packages collection where existing packages will be updated
     * @param pkgs
     * @return true when packages have been added successfully
     */
    public boolean addNewPkgs(final Collection<Pkg> pkgs) {
        if (!connected) { init(); }
        if (null == pkgs || pkgs.isEmpty()) {
            LOGGER.debug("Packages are null or empty.");
            return false;
        }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("New packages not added because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return false;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.PACKAGES_COLLECTION not set.");
            return false;
        };

        MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        ReplaceOptions replaceOptions = new ReplaceOptions().upsert(true);
        for (Pkg pkg : pkgs) {
            try {
                Document document = Document.parse(pkg.toString(OutputFormat.FULL_COMPRESSED));
                collection.replaceOne(eq(FIELD_PACKAGE_ID, pkg.getId()), document, replaceOptions);
            } catch (JsonParseException e) {
                LOGGER.error("Error parsing json when adding package {}. {}", pkg.getId(), e.getMessage());
            }
        }
        LOGGER.debug("Successfully added {} new packages to mongodb.", pkgs.size());
        return true;
    }

    /**
     * Removes the given list of packages from the packages collection
     * @param pkgs
     * @return true when packages have been removed successfully
     */
    public boolean removePkgs(final Collection<Pkg> pkgs) {
        if (!connected) { init(); }
        if (null == pkgs || pkgs.isEmpty()) {
            LOGGER.debug("Packages are null or empty.");
            return false;
        }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Packages have not been removed because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return false;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.PACKAGES_COLLECTION not set.");
            return false;
        };

        MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        for (Pkg pkg : pkgs) {
            try {
                Bson deleteFilter = eq(FIELD_FILENAME, pkg.getFileName());
                collection.deleteOne(deleteFilter);
            } catch (JsonParseException e) {
                LOGGER.error("Error when deleting package {}. {}", pkg.getId(), e.getMessage());
            }
        }
        LOGGER.debug("Successfully deleted {} packages from mongodb.", pkgs.size());
        return true;
    }

    /**
     * Removes all documents from the packages collection.
     * This method can be called from Updater.runOnceAtStartup() to wipe all documents
     * in case a new cache warmup file is provided that contains additional information
     * @return true when all documents have been removed successfully
     */
    public boolean removeAllPkgs() {
        if (!connected) { init(); }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Packages have not been removed because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return false;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.PACKAGES_COLLECTION not set.");
            return false;
        };

        MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        collection.deleteMany(new Document());

        LOGGER.debug("Successfully deleted all packages from mongodb.");
        return true;
    }

    /**
     * Returns a map with the packageId as key and the number of downloads as value.
     * With this one can determine which are most loaded packages.
     * @return a map with the packageId as key and the number of downloads as value
     */
    public Map<String, Long> getDowloads() {
        if (!connected) { init(); }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Cannot return downloads because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return new HashMap<>();
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.DOWNLOADS_COLLECTION) {
            LOGGER.error("Constants.DOWNLOADS_COLLECTION not set.");
            return new HashMap<>();
        };

        final Map<String, Long>  downloads        = new ConcurrentHashMap<>();
        final Consumer<Document> downloadConsumer = document -> downloads.put(document.getString(FIELD_PACKAGE_ID), document.getLong(FIELD_DOWNLOADS));

        final MongoCollection<Document> collection = database.getCollection(Constants.DOWNLOADS_COLLECTION);
        collection.find().forEach(downloadConsumer);

        LOGGER.debug("Successfully restored downloads for {} package ids from mongodb.", downloads.size());
        return downloads;
    }

    /**
     * Update number of downloads for given packageId
     * @param pkgId
     * @param noOfDownloads
     */
    public void upsertDownloadForId(final String pkgId, final Long noOfDownloads) {
        if (!connected) { init(); }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Could not upsert download because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.DOWNLOADS_COLLECTION) {
            LOGGER.error("Constants.DOWNLOADS_COLLECTION not set.");
            return;
        }
        database.getCollection(Constants.DOWNLOADS_COLLECTION)
                .updateOne(eq(FIELD_PACKAGE_ID, pkgId), combine(set(FIELD_PACKAGE_ID, pkgId), set(FIELD_DOWNLOADS, noOfDownloads)), new UpdateOptions().upsert(true));

        LOGGER.debug("Successfully updated no of downloads for id {}", pkgId);
    }

    public void updateLatestBuildAvailable(final List<Pkg> pkgs) {
        if (!connected) { init(); }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Could not update latest build available because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.PACKAGES_COLLECTION not set.");
            return;
        }
        MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        pkgs.forEach(pkg -> collection.updateOne(eq(FIELD_PACKAGE_ID, pkg.getId()), set(FIELD_LATEST_BUILD_AVAILABLE, false)));

        LOGGER.debug("Successfully updated latest build available for {} packages", pkgs.size());
    }

    public void syncLatestBuildAvailableInDatabaseWithCache(final Collection<Pkg> pkgs) {
        if (!connected) { init(); }
        if (null == Config.INSTANCE.getFoojayMongoDbDatabase()) {
            LOGGER.debug("Could not sync cache with database because FOOJAY_MONGODB_DATABASE environment variable was not set.");
            return;
        }
        if (null == database) {
            LOGGER.error("Database is not set.");
            database = mongoClient.getDatabase(Config.INSTANCE.getFoojayMongoDbDatabase());
        }
        if (null == Constants.PACKAGES_COLLECTION) {
            LOGGER.error("Constants.PACKAGES_COLLECTION not set.");
            return;
        }
        MongoCollection<Document> collection = database.getCollection(Constants.PACKAGES_COLLECTION);
        pkgs.forEach(pkg -> collection.updateOne(eq(FIELD_PACKAGE_ID, pkg.getId()), set(FIELD_LATEST_BUILD_AVAILABLE, pkg.isLatestBuildAvailable())));

        LOGGER.debug("Successfully synced latest build available for all packages in cache {}", pkgs.size());
    }
}
