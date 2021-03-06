/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.repositories.hdfs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.hadoop.hdfs.blobstore.HdfsBlobStore;
import org.elasticsearch.index.snapshots.IndexShardRepository;
import org.elasticsearch.repositories.RepositoryName;
import org.elasticsearch.repositories.RepositorySettings;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;
import org.elasticsearch.threadpool.ThreadPool;

public class HdfsRepository extends BlobStoreRepository implements FileSystemFactory {

    public final static String TYPE = "hdfs";

    private final HdfsBlobStore blobStore;
    private final BlobPath basePath;
    private final ByteSizeValue chunkSize;
    private final boolean compress;
    private final RepositorySettings repositorySettings;
    private FileSystem fs;

    @Inject
    public HdfsRepository(RepositoryName name, RepositorySettings repositorySettings, IndexShardRepository indexShardRepository, ThreadPool threadPool) throws IOException {
        super(name.getName(), repositorySettings, indexShardRepository);

        this.repositorySettings = repositorySettings;

        String path = repositorySettings.settings().get("path", settings.get("path"));
        if (path == null) {
            throw new IllegalArgumentException("no 'path' defined for hdfs snapshot/restore");
        }

        // get configuration
        fs = getFileSystem();
        Path hdfsPath = fs.makeQualified(new Path(path));
        this.basePath = BlobPath.cleanPath();

        logger.debug("Using file-system [{}] for URI [{}], path [{}]", fs, fs.getUri(), hdfsPath);
        blobStore = new HdfsBlobStore(settings, this, hdfsPath, threadPool);
        this.chunkSize = repositorySettings.settings().getAsBytesSize("chunk_size", settings.getAsBytesSize("chunk_size", null));
        this.compress = repositorySettings.settings().getAsBoolean("compress", settings.getAsBoolean("compress", false));
    }

    // as the FileSystem is long-lived and might go away, make sure to check it before it's being used.
    @Override
    public FileSystem getFileSystem() throws IOException {
        // check if the fs is still alive
        if (fs != null) {
            try {
                fs.getUsed();
            } catch (IOException ex) {
                if (ex.getMessage().contains("Filesystem closed")) {
                    fs = null;
                }
                else {
                    throw ex;
                }
            }
        }
        if (fs == null) {
            fs = initFileSystem(repositorySettings);
        }

        return fs;
    }

    private FileSystem initFileSystem(RepositorySettings repositorySettings) throws IOException {
        Configuration cfg = new Configuration(repositorySettings.settings().getAsBoolean("load_defaults", settings.getAsBoolean("load_defaults", true)));
        cfg.setClassLoader(this.getClass().getClassLoader());
        cfg.reloadConfiguration();

        String confLocation = repositorySettings.settings().get("conf_location", settings.get("conf_location"));
        if (Strings.hasText(confLocation)) {
            for (String entry : Strings.commaDelimitedListToStringArray(confLocation)) {
                addConfigLocation(cfg, entry.trim());
            }
        }

        Map<String, String> map = settings.getByPrefix("conf.").getAsMap();
        for (Entry<String, String> entry : map.entrySet()) {
            cfg.set(entry.getKey(), entry.getValue());
        }

        UserGroupInformation.setConfiguration(cfg);

        String uri = repositorySettings.settings().get("uri", settings.get("uri"));
        URI actualUri = (uri != null ? URI.create(uri) : FileSystem.getDefaultUri(cfg));
        String user = repositorySettings.settings().get("user", settings.get("user"));

        try {
            // disable FS cache
            String disableFsCache = String.format("fs.%s.impl.disable.cache", actualUri.getScheme());
            cfg.setBoolean(disableFsCache, true);
            return (user != null ? FileSystem.get(actualUri, cfg, user) : FileSystem.get(actualUri, cfg));
        } catch (Exception ex) {
            throw new ElasticsearchGenerationException(String.format("Cannot create Hdfs file-system for uri [%s]", actualUri), ex);
        }
    }

    private void addConfigLocation(Configuration cfg, String confLocation) {
        URL cfgURL = null;
        // it's an URL
        if (!confLocation.contains(":")) {
            cfgURL = cfg.getClassLoader().getResource(confLocation);

            // fall back to file
            if (cfgURL == null) {
                File file = new File(confLocation);
                if (!file.canRead()) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "Cannot find classpath resource or file 'conf_location' [%s] defined for hdfs snapshot/restore",
                                    confLocation));
                }
                String fileLocation = file.toURI().toString();
                logger.debug("Adding path [{}] as file [{}]", confLocation, fileLocation);
                confLocation = fileLocation;
            }
            else {
                logger.debug("Resolving path [{}] to classpath [{}]", confLocation, cfgURL);
            }
        }
        else {
            logger.debug("Adding path [{}] as URL", confLocation);
        }

        if (cfgURL == null) {
            try {
                cfgURL = new URL(confLocation);
            } catch (MalformedURLException ex) {
                throw new IllegalArgumentException(String.format(
                        "Invalid 'conf_location' URL [%s] defined for hdfs snapshot/restore", confLocation), ex);
            }
        }

        cfg.addResource(cfgURL);
    }

    @Override
    protected BlobStore blobStore() {
        return blobStore;
    }

    @Override
    protected BlobPath basePath() {
        return basePath;
    }

    @Override
    protected boolean isCompress() {
        return compress;
    }

    @Override
    protected ByteSizeValue chunkSize() {
        return chunkSize;
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        super.doClose();

        IOUtils.closeStream(fs);
        fs = null;
    }
}