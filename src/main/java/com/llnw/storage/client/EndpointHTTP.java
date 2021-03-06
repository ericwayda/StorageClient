package com.llnw.storage.client;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.CountingInputStream;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.MalformedJsonException;
import com.llnw.storage.client.io.ActivityCallback;
import com.llnw.storage.client.io.Chunk;
import com.llnw.storage.client.io.HeartbeatInputStream;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

@NotThreadSafe
public class EndpointHTTP implements EndpointMultipart {
    private static final Logger log = LoggerFactory.getLogger(EndpointHTTP.class);

    private static final String AUTH_HEADER = "X-Agile-Authorization";
    private static final String JSON_RPC_PATH = "/jsonrpc";


    private final URL endpoint;
    private final String username;
    private final String password;

    private final HttpClient client = new DefaultHttpClient();
    private final Gson gson = new Gson();
    private final JsonParser parser = new JsonParser();

    private int id;
    private String auth;
    private String mpid;
    private String lastQuery;    // The JSON that was sent to the HTTP API for the last query, for diagnostics
    private String lastResponse; // The response that was returned from the HTTP API, for diagnostics
    private int chunks;

    public EndpointHTTP(URL endpoint, String username, String password) {
        this.endpoint = endpoint;
        this.username = username;
        this.password = password;
    }


    @Override
    public void deleteDirectory(String path) throws IOException {
        int returnCode = execute(new RPC("deleteDir", "path", path)).getAsInt();
        if (returnCode != 0) { // 0 indicates success
            throw throwAndLog("Couldn't delete directory: " + returnCode);
        }
    }


    @Override
    public void deleteFile(String path) throws IOException {
        final int returnCode = execute(new RPC("deleteFile", "path", path)).getAsInt();
        if (returnCode != 0) { // 0 indicates success
            throw throwAndLog("Couldn't delete file: " + returnCode);
        }
    }


    @Override
    public void close() throws IOException {
        try {
            final JsonElement element = execute(new RPC("logout"));
            if (element.isJsonNull()) {
                throw throwAndLog("Couldn't logout");
            }

            final int returnCode = element.getAsInt();
            if (returnCode != 0) { // 0 indicates success
                throw throwAndLog("Couldn't logout: " + returnCode);
            }
        } finally {
            auth = null;
            client.getConnectionManager().closeIdleConnections(0, TimeUnit.MILLISECONDS);
        }
    }


    @Override
    public void makeDirectory(String path) throws IOException {
        // 0 indicates success, -2, -1 or 1 indicates the path already exists
        final ImmutableSet<Integer> successErrorCodes = ImmutableSet.of(-2, -1, 0, 1);
        final int returnCode = execute(new RPC("makeDir2", "path", path)).getAsInt();

        if (!successErrorCodes.contains(returnCode)) {
            throw throwAndLog("Couldn't make directory: " + returnCode);
        } // Otherwise success, do nothing
    }


    @Override
    public List<String> listFiles(String path) throws IOException {
        final RPC call = new RPC("listFile", "path", path);
        final JsonElement list = execute(call).getAsJsonObject().get("list");

        if (list == null) {
            return Lists.newArrayList();
        }

        final List<NameUnmangler> nameUnmanglers = Arrays.asList(gson.fromJson(list, NameUnmangler[].class));

        return Lists.newArrayList(Lists.transform(nameUnmanglers, new Function<NameUnmangler, String>() {
            @Override
            public String apply(@Nullable NameUnmangler o) {
                return o == null ? "" : o.name;
            }
        }));
    }


    @Override
    public boolean exists(String path) throws IOException {
        final RPC call = new RPC("stat", "path", path);
        final JsonElement stat = execute(call).getAsJsonObject().get("code");

        return stat.getAsInt() == 0;
    }


    @Override
    public void noop() throws IOException {
        execute(new RPC("noop", "operation", "lvp"));
    }


    @Override
    public String startMultipartUpload(String path, String name) throws IOException {
        final RPC call = new RPC("createMultipart", "path", path + "/" + name);
        final JsonElement mpid = execute(call).getAsJsonObject().get("mpid");

        this.mpid = mpid.getAsString();
        chunks = 1;
        return this.mpid;
    }


    @Override
    public void completeMultipartUpload() throws IOException {
        if (mpid == null)
            throw new IllegalArgumentException("Must call startUpload before completeUpload");

        final RPC call = new RPC("completeMultipart", "mpid", mpid);
        final JsonObject result = execute(call).getAsJsonObject();
        if (!result.has("code"))
            throw throwAndLog("No response code from complete multipart upload with mpid(" + mpid + ")");
        final int returnCode = result.get("code").getAsInt();
        if (!result.has("numpieces"))
            throw throwAndLog("No numpieces from complete multipart upload with mpid(" + mpid + ")");
        final int returnedChunks = result.get("numpieces").getAsInt();

        if (returnCode != 0 || returnedChunks != chunks - 1) {
            // 0 indicates success
            throw throwAndLog("Couldn't complete multipart upload with mpid(" + mpid + "): " + returnCode);
        }
    }


    @Override
    public void abortMultipartUpload() throws IOException {
        if (mpid == null)
            throw new IllegalArgumentException("Must call startUpload before abortUpload");

        final RPC call = new RPC("abortMultipart", "mpid", mpid);
        final int returnCode = execute(call).getAsJsonObject().get("code").getAsInt();

        if (returnCode != 0) {
            // 0 indicates success
            throw throwAndLog("Couldn't abort multipart upload with mpid(" + mpid + "): " + returnCode);
        }

        this.mpid = null;
    }


    @Override
    public void uploadPart(File file, Iterator<Chunk> chunkIterator, @Nullable ActivityCallback callback)
            throws IOException {
        requireAuth();

        if (mpid == null)
            throw new IllegalArgumentException("Must call startUpload before uploadPart");

        @SuppressWarnings("resource")
        final FileChannel fc = new FileInputStream(file).getChannel();
        try {
            while(chunkIterator.hasNext()) {
                final Chunk chunk = chunkIterator.next();
                int toUploadChunk = chunks;

                if (!chunk.appending) {
                    // Need to figure out which chunk this is updating
                    final int pageSize = 100;
                    int startChunk = 1;
                    int ret = -1;

                    while (startChunk < chunks &&
                            (ret = whichChunkOffset(startChunk, chunk.offset, pageSize)) == -1) {
                        startChunk += pageSize;
                    }

                    if (startChunk > chunks) {
                        throw throwAndLog("Couldn't find chunk with offset: " + chunk.offset);
                    }

                    toUploadChunk = ret;
                }

                final HttpPost post = new HttpPost(endpoint.toString() + "/multipart/piece");
                try {
                    post.addHeader(AUTH_HEADER, auth);
                    post.addHeader("X-Agile-Part", Integer.toString(toUploadChunk));
                    post.addHeader("X-Agile-Multipart", mpid);

                    final InputStream is = HeartbeatInputStream.wrap(fc, chunk, callback);
                    final String sha256 = DigestUtils.sha256Hex(is);
                    is.reset();

                    final InputStreamEntity entity = new InputStreamEntity(is, chunk.length);

                    post.setEntity(entity);
                    final HttpResponse response = client.execute(post);
                    final int status = response.getStatusLine().getStatusCode();

                    if (status == HttpStatus.SC_OK) {
                        final Map<String, String> headerChecks = ImmutableMap.of(
                                "X-Agile-Status", "0",
                                "X-Agile-Size", Long.toString(chunk.length),
                                "X-Agile-Checksum", sha256);
                        checkHeaders(response, headerChecks);
                    } else {
                        throw throwAndLog("Got status: " + response.getStatusLine().getStatusCode() + " from upload");
                    }
                } finally {
                    post.releaseConnection();
                }

                if (toUploadChunk == chunks) {
                    // This was appending, so increment the number of chunks
                    chunks++;
                }
            }
        } catch (IOException e) {
            throw EndpointUtil.unwindInterruptException(e);
        } finally {
            fc.close();
        }
    }

    @Override
    public void upload(File file, String path, String name, @Nullable ActivityCallback callback) throws IOException {
        this.upload(new HeartbeatInputStream(file, callback), path, name);
    }

    @Override
    public void upload(ByteBuffer byteBuffer, String path, String name, @Nullable ActivityCallback callback) throws IOException {
        this.upload(HeartbeatInputStream.wrap(byteBuffer, callback), path, name);
    }


    private void upload(HeartbeatInputStream heartbeatInputStream, String path, String name) throws IOException {
        requireAuth();

        final HttpPost post = new HttpPost(endpoint.toString() + "/post/file");

        try {
            post.addHeader(AUTH_HEADER, auth);

            final MultipartEntity entity = new MultipartEntity();
            entity.addPart("directory", new StringBody(path, Charsets.UTF_8));
            entity.addPart("basename", new StringBody(name, Charsets.UTF_8));
            final DigestInputStream digestStream = new DigestInputStream(heartbeatInputStream, MessageDigest.getInstance("SHA-256"));
            final CountingInputStream countStream = new CountingInputStream(digestStream);
            final InputStreamBody body = new InputStreamBody(countStream, name);
            entity.addPart("uploadFile", body);

            post.setEntity(entity);
            this.lastQuery = "upload to " + path + "/" + name;

            final HttpResponse response = client.execute(post);
            this.lastResponse = responseToString(response);

            final int status = response.getStatusLine().getStatusCode();

            if (status == HttpStatus.SC_OK) {
                final String sha256 = Hex.encodeHexString(digestStream.getMessageDigest().digest());
                final Map<String, String> headerChecks = ImmutableMap.of(
                        "X-Agile-Status", "0",
                        "X-Agile-Size", Long.toString(countStream.getCount()),
                        "X-Agile-Checksum", sha256);
                checkHeaders(response, headerChecks);
            } else {
                throw throwAndLog("Got status: " + response.getStatusLine().getStatusCode() + " from upload");
            }
        } catch (IOException e) {
            throw EndpointUtil.unwindInterruptException(e);
        } catch (NoSuchAlgorithmException e) {
            Throwables.propagate(e);
        } finally {
            post.releaseConnection();
        }
    }


    @Override
    public void setMpid(String mpid) {
        this.mpid = mpid;
    }


    @Override
    public void resumeMultipartUpload() throws IOException {
        if (mpid == null)
            throw new IllegalArgumentException("Must call startUpload or setMpid before this");

        final RPC call = new RPC("restartMultipart", "mpid", mpid);
        final JsonObject elem = execute(call).getAsJsonObject();
        int code = 0;
        if (!elem.has("code") || (code = elem.get("code").getAsInt()) != 0) {
            throw throwAndLog("Invalid mpid: " + code + " obj: " + elem);
        }
    }


    @Override
    public MultipartStatus getMultipartStatus() throws IOException {
        if (mpid == null)
            throw new IllegalArgumentException("Must call startUpload or setMpid before this");

        final RPC call = new RPC("getMultipartStatus", "mpid", mpid);
        final JsonObject elem = execute(call).getAsJsonObject();
        int code = 0;
        if (!elem.has("code") || (code = elem.get("code").getAsInt()) != 0) {
            throw throwAndLog("Invalid mpid: " + code + " obj: " + elem);
        }

        if (!elem.has("state")) {
            throw throwAndLog("Invalid returned object");
        }

        try {
            return MultipartStatus.fromInt(elem.get("state").getAsInt());
        } catch (IllegalArgumentException e) {
            throw throwAndLog(e.getMessage());
        }
    }


    @Override
    public List<MultipartPiece> listMultipartPiece(int lastPiece, int pageSize) throws IOException {
        if (mpid == null)
            throw new IllegalArgumentException("Must call startUpload or setMpid before this");

        final RPC call = new RPC("listMultipartPiece", "mpid", mpid, "cookie", lastPiece, "pagesize", pageSize);

        final JsonObject ret = execute(call).getAsJsonObject();
        int code = 0;
        if (!ret.has("code") || (code = ret.get("code").getAsInt()) != 0 || !ret.has("pieces")) {
            // Failure
            throw throwAndLog("Invalid code for listMultipartPiece: " + code + " obj: " + ret);
        }

        return Arrays.asList(gson.fromJson(ret.get("pieces").getAsJsonArray(), MultipartPiece[].class));
    }

    private int whichChunkOffset(final int startingChunk, final long targetOffset, final Integer pageSize) throws IOException {
        final RPC call = new RPC("listMultipartPiece",
                "mpid", mpid, "cookie", Integer.toString(startingChunk), "pagesize", new Integer(pageSize));

        final JsonObject ret = execute(call).getAsJsonObject();
        int code = 0;
        if (!ret.has("code") || (code = ret.get("code").getAsInt()) != 0 || !ret.has("pieces")) {
            // Failure
            throw throwAndLog("Invalid code for listMultipartPiece: " + code + " obj: " + ret);
        }

        final JsonArray pieces = ret.get("pieces").getAsJsonArray();
        long offset = 0;
        int chunk = startingChunk;
        for (JsonElement elem : pieces) {
            final JsonObject obj = elem.getAsJsonObject();
            offset += obj.get("size").getAsLong();

            if (offset > targetOffset)
                return chunk;
            else
                chunk++;
        }

        return -1; // not found
    }


    private void checkHeaders(HttpResponse response, Map<String, String> headerChecks) throws EndpointException {
        for (Entry<String, String> headerCheck : headerChecks.entrySet()) {
            final Header h = response.getFirstHeader(headerCheck.getKey());
            if (h == null || !h.getValue().equalsIgnoreCase(headerCheck.getValue())) {
                throw throwAndLog(headerCheck.getKey() +
                        ", got: " + h.getValue() + ", expected: " + headerCheck.getValue());
            }
        }
    }


    private JsonElement execute(RPC args) throws IOException {
        return execute(args, null);
    }


    private JsonElement execute(RPC args, @Nullable Map<String, String> checkHeaders) throws IOException {
        requireAuth();

        final HttpPost post = new HttpPost(endpoint.toString() + JSON_RPC_PATH);
        post.addHeader(AUTH_HEADER, auth);
        args.params.put("token", auth);
        final String message = gson.toJson(args);

        String response = "";
        try {
            this.lastQuery = message;
            post.setEntity(new StringEntity(message));

            final HttpResponse httpResponse = client.execute(post);
            response = responseToString(httpResponse);
            this.lastResponse = response;

            final int status = httpResponse.getStatusLine().getStatusCode();

            if (status != HttpStatus.SC_OK) {
                throw throwAndLog("Got status: " + status + " from method: " + args.method);
            } else if (checkHeaders != null) {
                checkHeaders(httpResponse, checkHeaders);
            }

            final JsonObject obj = parser.parse(response).getAsJsonObject();

            if (obj.has("result")) {
                return obj.get("result");
            } else {
                throw throwAndLog("No result field");
            }
        } catch (JsonSyntaxException e) {
            log.error("JsonSyntaxException from {}", response, e);
            throw e;
        } catch (MalformedJsonException e) {
            log.error("Bad JSON {}", response, e);
            throw e;
        } catch (IOException e) {
            throw EndpointUtil.unwindInterruptException(e);
        } finally {
            post.releaseConnection();
        }
    }


    private JsonElement getResult(String response) throws IOException {
        try {
            final JsonObject obj = parser.parse(response).getAsJsonObject();
            final JsonElement err = obj.get("error");

            if (err != null && !err.isJsonNull()) {
                throw throwAndLog(obj.get("error").getAsString());
            }

            return obj.get("result");
        } catch (JsonSyntaxException e) {
            log.error("JsonSyntaxException from {} in getResult", response, e);
            throw e;
        }
    }


    private String responseToString(final HttpResponse response) throws IOException {
        return IOUtils.toString(response.getEntity().getContent(), Charsets.UTF_8);
    }


    private void requireAuth() throws IOException {
        if (!Strings.isNullOrEmpty(auth)) return;

        final HttpPost post = new HttpPost(endpoint.toString() + JSON_RPC_PATH);
        final String message = gson.toJson(new RPC("login", "username", username, "password", password));

        try {
            post.setEntity(new StringEntity(message));
            final String response = responseToString(client.execute(post));

            final JsonArray array = getResult(response).getAsJsonArray();
            auth = gson.fromJson(array.get(0), String.class);

            if (Strings.isNullOrEmpty(auth)) {
                throw throwAndLog("Null or empty auth, response: " + response);
            }
        } finally {
            post.releaseConnection();
        }
    }

    private EndpointException throwAndLog(String message) throws EndpointException {
        log.error(message + "\n  Query(" + this.lastQuery + ")\n  Response(" + this.lastResponse + ")");
        throw new EndpointException(message);
    }


    @SuppressWarnings({"unused", "serial"})
    private class RPC {
        private final String jsonrpc = "2.0";
        private final String method;
        private final Map<String,Object> params;
        private final int id;


        private RPC(final String method) {
            this(method, new HashMap<String, Object>());
        }


        private RPC(final String method, final String paramOneName, final Object paramOneValue) {
            this(method, new HashMap<String, Object>() {{
                put(paramOneName, paramOneValue);
            }});
        }


        private RPC(final String method,
                final String paramOneName, final Object paramOneValue,
                final String paramTwoName, final Object paramTwoValue) {
            this(method, new HashMap<String, Object>() {{
                put(paramOneName, paramOneValue);
                put(paramTwoName, paramTwoValue);
            }});
        }


        private RPC(final String method,
                final String paramOneName, final Object paramOneValue,
                final String paramTwoName, final Object paramTwoValue,
                final String paramThreeName, final Object paramThreeValue) {
            this(method, new HashMap<String, Object>() {{
                put(paramOneName, paramOneValue);
                put(paramTwoName, paramTwoValue);
                put(paramThreeName, paramThreeValue);
            }});
        }


        private RPC(String method, Map<String, Object> params) {
            this.method = method;
            this.params = params;
            this.id = ++EndpointHTTP.this.id;
        }
    }


    @SuppressWarnings("unused")
    private class NameUnmangler {
        private final String name;
        private final int type;

        private NameUnmangler(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }
}
