package com.drarabi.medvision;

import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiBridge {

    private static final String TAG = "ApiBridge";
    private static final String ANALYZE_URL = "https://www.medgpt.net/api/analyze";
    private static final String FOLLOWUP_URL = "https://www.medgpt.net/api/followup";

    private final WebView webView;

    public ApiBridge(WebView webView) {
        this.webView = webView;
    }

    @JavascriptInterface
    public String request(String jsonParams) {
        Log.d(TAG, "Request received: " + jsonParams);
        try {
            JSONObject params = new JSONObject(jsonParams);
            String action = params.optString("action", "");
            String accessToken = params.optString("access_token", "");

            if (accessToken.isEmpty()) {
                return new JSONObject().put("error", "Access Token missing").toString();
            }

            if ("analyze".equals(action)) {
                return handleAnalyze(params, accessToken);
            } else if ("followup".equals(action)) {
                return handleFollowup(params, accessToken);
            } else {
                return new JSONObject().put("error", "Unknown action: " + action).toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing request", e);
            try {
                return new JSONObject().put("error", "Bridge error: " + e.getMessage()).toString();
            } catch (Exception ex) {
                return "{\"error\":\"Bridge error\"}";
            }
        }
    }

    private String handleAnalyze(JSONObject params, String accessToken) throws Exception {
        String mode = params.optString("mode", "standard");
        String modality = params.optString("modality", "auto");
        String provider = params.optString("provider", "gemini");
        String specialConcern = params.optString("special_concern", "");

        String boundary = "----AndroidFormBoundary" + System.currentTimeMillis();
        String delimiter = "--" + boundary;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Text fields
        writeFormField(dos, delimiter, "modality", modality);
        writeFormField(dos, delimiter, "task", "auto");
        writeFormField(dos, delimiter, "provider", provider);
        writeFormField(dos, delimiter, "mode", mode);

        if (!specialConcern.isEmpty()) {
            writeFormField(dos, delimiter, "special_concern", specialConcern);
        }

        // Files
        JSONArray files = params.optJSONArray("files");
        if (files != null && files.length() > 0) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject fileObj = files.getJSONObject(i);
                String fileName = fileObj.optString("name", "image.jpg");
                String fileType = fileObj.optString("type", "image/jpeg");
                String base64Data = fileObj.optString("data", "");

                if (!base64Data.isEmpty()) {
                    byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);
                    writeFileField(dos, delimiter, "files", fileName, fileType, fileBytes);
                }
            }
        }

        // End boundary
        dos.writeBytes(delimiter + "--\r\n");
        dos.flush();

        byte[] postData = baos.toByteArray();

        // Check if at least one file or text is provided
        if (mode.equals("standard") && (files == null || files.length() == 0)) {
            return new JSONObject().put("error", "Standard mode requires at least one image.").toString();
        }
        if ((files == null || files.length() == 0) && specialConcern.isEmpty()) {
            return new JSONObject().put("error", "Please provide an image or text input.").toString();
        }

        return executeMultipartPost(ANALYZE_URL, accessToken, boundary, postData);
    }

    private String handleFollowup(JSONObject params, String accessToken) throws Exception {
        String requestId = params.optString("request_id", "");
        String question = params.optString("question", "");

        if (requestId.isEmpty() || question.isEmpty()) {
            return new JSONObject().put("error", "Missing request_id or question").toString();
        }

        JSONObject payload = new JSONObject();
        payload.put("request_id", requestId);
        payload.put("question", question);

        return executeJsonPost(FOLLOWUP_URL, accessToken, payload.toString());
    }

    private void writeFormField(DataOutputStream dos, String delimiter, String name, String value) throws Exception {
        dos.writeBytes(delimiter + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        dos.writeBytes(value + "\r\n");
    }

    private void writeFileField(DataOutputStream dos, String delimiter, String name, String fileName,
                                 String contentType, byte[] data) throws Exception {
        dos.writeBytes(delimiter + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n");
        dos.writeBytes("Content-Type: " + contentType + "\r\n\r\n");
        dos.write(data);
        dos.writeBytes("\r\n");
    }

    private String executeMultipartPost(String urlString, String accessToken, String boundary,
                                         byte[] postData) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Origin", "https://www.medgpt.net");
            conn.setRequestProperty("Referer", "https://www.medgpt.net/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36");
            conn.setRequestProperty("X-Requested-With", "mark.via.gp");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            // Write data
            OutputStream os = conn.getOutputStream();
            os.write(postData);
            os.flush();
            os.close();

            return readResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "Multipart POST error", e);
            try {
                return new JSONObject()
                        .put("error", "MedGPT API Error")
                        .put("detail", e.getMessage())
                        .toString();
            } catch (Exception ex) {
                return "{\"error\":\"MedGPT API Error\"}";
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String executeJsonPost(String urlString, String accessToken, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Origin", "https://www.medgpt.net");
            conn.setRequestProperty("Referer", "https://www.medgpt.net/");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.flush();
            os.close();

            return readResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "JSON POST error", e);
            try {
                return new JSONObject()
                        .put("error", "MedGPT Followup API Error")
                        .put("detail", e.getMessage())
                        .toString();
            } catch (Exception ex) {
                return "{\"error\":\"MedGPT Followup API Error\"}";
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readResponse(HttpURLConnection conn) {
        try {
            int httpCode = conn.getResponseCode();

            InputStream is;
            if (httpCode >= 200 && httpCode < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
                if (is == null) is = conn.getInputStream();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String responseBody = response.toString();

            if (httpCode == 200) {
                return responseBody;
            } else {
                try {
                    JSONObject errJson = new JSONObject(responseBody);
                    errJson.put("code", httpCode);
                    errJson.put("http_status", httpCode);
                    return errJson.toString();
                } catch (Exception e) {
                    JSONObject err = new JSONObject();
                    err.put("error", "MedGPT API Error");
                    err.put("code", httpCode);
                    err.put("detail", responseBody);
                    return err.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Read response error", e);
            try {
                return new JSONObject()
                        .put("error", "MedGPT API Error")
                        .put("detail", e.getMessage())
                        .toString();
            } catch (Exception ex) {
                return "{\"error\":\"MedGPT API Error\"}";
            }
        }
    }
}
