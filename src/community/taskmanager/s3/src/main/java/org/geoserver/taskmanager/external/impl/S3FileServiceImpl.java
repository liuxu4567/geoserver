/* (c) 2018 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.taskmanager.external.impl;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.commons.io.FileUtils;
import org.geoserver.taskmanager.external.FileReference;
import org.geoserver.taskmanager.external.FileService;
import org.geotools.util.logging.Logging;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3 remote file storage.
 *
 * @author Timothy De Bock - timothy.debock.github@gmail.com
 */
public class S3FileServiceImpl implements FileService {

    private static final long serialVersionUID = -5960841858385823283L;
    
    private static final Logger LOGGER = Logging.getLogger(S3FileServiceImpl.class);

    private static String ENCODING = "aws-chunked";

    private String alias;

    private String endpoint;

    private String user;

    private String password;

    private String rootFolder;

    private static String S3_NAME_PREFIX = "s3-";
    
    public static String name(String prefix, String bucket) {
        return S3_NAME_PREFIX + prefix + "-" + bucket;
    }

    public S3FileServiceImpl() {
    }

    public S3FileServiceImpl(String endpoint, String user, String password, String alias, String rootFolder) {
        this.endpoint = endpoint;
        this.user = user;
        this.password = password;
        this.alias = alias;
        this.rootFolder = rootFolder;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public void setRootFolder(String rootFolder) {
        this.rootFolder = rootFolder;
    }

    @Override
    public String getRootFolder() {
        return rootFolder;
    }

    @Override
    public String getName() {
        return name(alias, rootFolder);
    }

    @Override
    public String getDescription() {
        return "S3 Service: " + alias + "/" + rootFolder;
    }

    @Override
    public boolean checkFileExists(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("Name of a file can not be null.");
        }
        try {
            return getS3Client().doesObjectExist(rootFolder, filePath);
        } catch (AmazonClientException e) {
            throw new IOException(e);
        }

    }

    @Override
    public void create(String filePath, InputStream content) throws IOException {
        //Check parameters
        if (content == null) {
            throw new IllegalArgumentException("Content of a file can not be null.");
        }
        if (filePath == null) {
            throw new IllegalArgumentException("Name of a file can not be null.");
        }

        if (checkFileExists(filePath)) {
            throw new IllegalArgumentException("The file already exists");
        }
        File scratchFile = File.createTempFile("prefix", String.valueOf(System.currentTimeMillis()));
        try {
            if (!getS3Client().doesBucketExist(rootFolder)) {
                getS3Client().createBucket(rootFolder);
            }

            FileUtils.copyInputStreamToFile(content, scratchFile);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentEncoding(ENCODING);

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    rootFolder,
                    filePath,
                    scratchFile);

            putObjectRequest.withMetadata(metadata);

            getS3Client().putObject(putObjectRequest);
        } catch (AmazonClientException e) {
            throw new IOException(e);
        } finally {
            if (scratchFile.exists()) {
                scratchFile.delete();
            }
        }
    }

    @Override
    public boolean delete(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("Name of a file can not be null.");
        }
        if (checkFileExists(filePath)) {
            try {
                getS3Client().deleteObject(rootFolder, filePath);
            } catch (AmazonClientException e) {
                throw new IOException(e);
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public InputStream read(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("Name of a file can not be null.");
        }
        GetObjectRequest objectRequest = new GetObjectRequest(rootFolder, filePath);
        try {
            return getS3Client().getObject(objectRequest).getObjectContent();
        } catch (AmazonClientException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<String> listSubfolders() {
        Set<String> paths = new HashSet<>();
        ObjectListing listing = getS3Client().listObjects(rootFolder);
        for (S3ObjectSummary summary : listing.getObjectSummaries()) {
            String fullPath = summary.getKey();
            int countSeparators = fullPath.length() - fullPath.replace("/", "").length();
            int fromIndex = 0;
            for (int i = 0; i < countSeparators; i++) {
                int indexOfSeparator = fullPath.indexOf("/", fromIndex);
                fromIndex = indexOfSeparator + 1;
                paths.add(fullPath.substring(0, indexOfSeparator));
            }
        }
        return new ArrayList<>(paths);
    }

    @Override
    public FileReference getVersioned(String filePath) {
        int index = filePath.indexOf(PLACEHOLDER_VERSION);
        if (index < 0) {
            return new FileReferenceImpl(this, filePath, filePath);
        }
        
        SortedSet<Integer> set = new TreeSet<Integer>();
        Pattern pattern = Pattern.compile(Pattern.quote(filePath)
                .replace(FileService.PLACEHOLDER_VERSION, "\\E(.*)\\Q"));       
                        
        ObjectListing listing = getS3Client().listObjects(rootFolder, filePath.substring(0, index));
        for (S3ObjectSummary summary : listing.getObjectSummaries()) {
            Matcher matcher = pattern.matcher(summary.getKey());
            if (matcher.matches()) {
                try {
                    set.add(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException e) {
                    LOGGER.log(Level.WARNING, "could not parse version in versioned file " + summary.getKey(), e);
                }
            }
        }
        int last = set.isEmpty() ? 0 : set.last();
        return new FileReferenceImpl(this, filePath.replace(FileService.PLACEHOLDER_VERSION, last + ""),
                filePath.replace(FileService.PLACEHOLDER_VERSION, (last + 1) + ""));
        
    }

    @Override
    public URI getURI(String filePath) {
        try {
            return new URI(alias + "://" + rootFolder + "/" + filePath);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private AmazonS3 getS3Client() {
        if (endpoint == null) {
            throw new IllegalArgumentException("The endpoint is required, add a property: alias.s3.endpoint");
        }
        if (user == null) {
            throw new IllegalArgumentException("The user is required, add a property: alias.s3.user");
        }
        if (password == null) {
            throw new IllegalArgumentException("The password is required, add a property: alias.s3.password");
        }
        if (rootFolder == null) {
            throw new IllegalStateException("The rootfolder is required, add a property: alias.s3.rootfolder");
        }

        AmazonS3 s3;
        //custom endpoint

        s3 = new AmazonS3Client(new BasicAWSCredentials(user, password));

        final S3ClientOptions clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).build();
        s3.setS3ClientOptions(clientOptions);
        String endpoint = this.endpoint;
        if (!endpoint.endsWith("/")) {
            endpoint = endpoint + "/";
        }
        s3.setEndpoint(endpoint);

        return s3;
    }


}