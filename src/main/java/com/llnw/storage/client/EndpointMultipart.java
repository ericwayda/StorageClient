package com.llnw.storage.client;

import com.llnw.storage.client.io.ActivityCallback;
import com.llnw.storage.client.io.Chunk;
import org.joda.time.Duration;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public interface EndpointMultipart extends Endpoint {
    public String startMultipartUpload(String path, String name) throws IOException;
    public void setMpid(String mpid);
    public void resumeMultipartUpload() throws IOException;
    public void uploadPart(File file, Iterator<Chunk> chunks, @Nullable ActivityCallback callback) throws IOException;
    public void abortMultipartUpload() throws IOException;
    public MultipartStatus getMultipartStatus() throws IOException;
    public void completeMultipartUpload() throws IOException;
}
