package com.reactnative.ivpusic.imagepicker;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import android.Manifest;
import android.os.Environment;

import com.facebook.react.ReactActivity;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;

import android.support.v4.app.ActivityCompat;
import android.content.pm.PackageManager;
import android.webkit.MimeTypeMap;

import com.facebook.react.modules.core.PermissionListener;
import com.yalantis.ucrop.UCrop;

import java.io.File;
import java.util.*;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Created by ipusic on 5/16/16.
 */
public class PickerModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private static final int IMAGE_PICKER_REQUEST = 1062228920;
    private static final int CAMERA_PICKER_REQUEST = 1062228921;
    private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";

    private static final String E_PICKER_CANCELLED_KEY = "E_PICKER_CANCELLED";
    private static final String E_PICKER_CANCELLED_MSG = "User cancelled image selection";

    private static final String E_CALLBACK_ERROR = "E_CALLBACK_ERROR";
    private static final String E_FAILED_TO_SHOW_PICKER = "E_FAILED_TO_SHOW_PICKER";
    private static final String E_FAILED_TO_OPEN_CAMERA = "E_FAILED_TO_OPEN_CAMERA";
    private static final String E_NO_IMAGE_DATA_FOUND = "E_NO_IMAGE_DATA_FOUND";
    private static final String E_CAMERA_IS_NOT_AVAILABLE = "E_CAMERA_IS_NOT_AVAILABLE";
    private static final String E_CANNOT_LAUNCH_CAMERA = "E_CANNOT_LAUNCH_CAMERA";
    private static final String E_PERMISSIONS_MISSING = "E_PERMISSIONS_MISSING";
    private static final String E_ERROR_WHILE_CLEANING_FILES = "E_ERROR_WHILE_CLEANING_FILES";

    private Promise mPickerPromise;

    private boolean cropping = false;
    private boolean circular = false;
    private boolean multiple = false;
    private boolean includeBase64 = false;
    private int width = 200;
    private int height = 200;
    private Boolean tmpImage;
    private final ReactApplicationContext mReactContext;
    private Uri mCameraCaptureURI;

    public PickerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
        mReactContext = reactContext;
    }

    public String getTmpDir() {
        String tmpDir = mReactContext.getCacheDir() + "/react-native-image-crop-picker";
        Boolean created = new File(tmpDir).mkdir();

        System.out.println(tmpDir);

        return tmpDir;
    }

    @Override
    public String getName() {
        return "ImageCropPicker";
    }

    private void setConfiguration(final ReadableMap options) {
        multiple = options.hasKey("multiple") && options.getBoolean("multiple");
        includeBase64 = options.hasKey("includeBase64") && options.getBoolean("includeBase64");
        width = options.hasKey("width") ? options.getInt("width") : width;
        height = options.hasKey("height") ? options.getInt("height") : height;
        cropping = options.hasKey("cropping") ? options.getBoolean("cropping") : cropping;
        circular = options.hasKey("circular") ? options.getBoolean("circular") : circular;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        fileOrDirectory.delete();
    }

    @ReactMethod
    public void clean(final Promise promise) {

        final Activity activity = getCurrentActivity();
        final PickerModule module = this;

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    File file = new File(module.getTmpDir());
                    if (!file.exists()) throw new Exception("File does not exist");

                    module.deleteRecursive(file);
                    promise.resolve(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    promise.reject(E_ERROR_WHILE_CLEANING_FILES, ex.getMessage());
                }

                return null;
            }
        });
    }

    @ReactMethod
    public void cleanSingle(final String pathToDelete, final Promise promise) {
        if (pathToDelete == null) {
            promise.reject(E_ERROR_WHILE_CLEANING_FILES, "Cannot cleanup empty path");
            return;
        }

        final Activity activity = getCurrentActivity();
        final PickerModule module = this;

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    String path = pathToDelete;
                    final String filePrefix = "file://";
                    if (path.startsWith(filePrefix)) {
                        path = path.substring(filePrefix.length());
                    }

                    File file = new File(path);
                    if (!file.exists()) throw new Exception("File does not exist. Path: " + path);

                    module.deleteRecursive(file);
                    promise.resolve(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    promise.reject(E_ERROR_WHILE_CLEANING_FILES, ex.getMessage());
                }

                return null;
            }
        });
    }

    private void permissionsCheck(final Activity activity, final Promise promise, final List<String> requiredPermissions, final Callable<Void> callback) {

        List<String> missingPermissions = new ArrayList<>();

        for (String permission : requiredPermissions) {
            int status = ActivityCompat.checkSelfPermission(activity, permission);
            if (status != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {

            ((ReactActivity) activity).requestPermissions(missingPermissions.toArray(new String[missingPermissions.size()]), 1, new PermissionListener() {

                @Override
                public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                    if (requestCode == 1) {

                        for (int i = 0; i < grantResults.length; i++) {
                            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                                promise.reject(E_PERMISSIONS_MISSING, "Required permission missing");
                                return true;
                            }
                        }

                        try {
                            callback.call();
                        } catch (Exception e) {
                            promise.reject(E_CALLBACK_ERROR, "Unknown error", e);
                        }
                    }

                    return true;
                }
            });

            return;
        }

        // all permissions granted
        try {
            callback.call();
        } catch (Exception e) {
            promise.reject(E_CALLBACK_ERROR, "Unknown error", e);
        }
    }

    @ReactMethod
    public void openCamera(final ReadableMap options, final Promise promise) {

        if (!isCameraAvailable()) {
            promise.reject(E_CAMERA_IS_NOT_AVAILABLE, "Camera not available");
            return;
        }

        final Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        setConfiguration(options);
        mPickerPromise = promise;

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initiateCamera(activity);
                return null;
            }
        });
    }

    private void initiateCamera(Activity activity) {

        try {

            int requestCode = CAMERA_PICKER_REQUEST;
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            tmpImage = true;
            // we create a tmp file to save the result
            File imageFile = createNewFile(true);
            mCameraCaptureURI = Uri.fromFile(imageFile);

            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraCaptureURI);
            if (cameraIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
                mPickerPromise.reject(E_CANNOT_LAUNCH_CAMERA, "Cannot launch camera");
                return;
            }

            activity.startActivityForResult(cameraIntent, requestCode);
        } catch (Exception e) {
            mPickerPromise.reject(E_FAILED_TO_OPEN_CAMERA, e);
        }

    }

    public void initiatePicker(final Activity activity) {
        try {
            final Intent galleryIntent = new Intent(Intent.ACTION_PICK);

            if (cropping) {
                galleryIntent.setType("image/*");
            } else {
                galleryIntent.setType("image/*,video/*");
            }

            galleryIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            galleryIntent.setAction(Intent.ACTION_GET_CONTENT);
            galleryIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

            final Intent chooserIntent = Intent.createChooser(galleryIntent, "Pick an image");
            activity.startActivityForResult(chooserIntent, IMAGE_PICKER_REQUEST);
        } catch (Exception e) {
            mPickerPromise.reject(E_FAILED_TO_SHOW_PICKER, e);
        }
    }

    @ReactMethod
    public void openPicker(final ReadableMap options, final Promise promise) {
        final Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        setConfiguration(options);
        mPickerPromise = promise;

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initiatePicker(activity);
                return null;
            }
        });
    }

    private String getBase64StringFromFile(String absoluteFilePath) {
        InputStream inputStream;

        try {
            inputStream = new FileInputStream(new File(absoluteFilePath));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }

        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        bytes = output.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }

        return type;
    }

    public WritableMap getSelection(Activity activity, Uri uri) throws Exception {
        String path = RealPathUtil.getRealPathFromURI(activity, uri);
        if (path == null || path.isEmpty()) {
            throw new Exception("Cannot resolve image path.");
        }

        String mime = getMimeType(path);
        if (mime != null && mime.startsWith("video/")) {
            return getVideo(path, mime);
        }

        return getImage(activity, uri, true);
    }

    public WritableMap getVideo(String path, String mime) {
        WritableMap image = new WritableNativeMap();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(path);
        Bitmap bmp = retriever.getFrameAtTime();

        if (bmp != null) {
            image.putInt("width", bmp.getWidth());
            image.putInt("height", bmp.getHeight());
        }

        image.putString("path", "file://" + path);
        image.putString("mime", mime);
        image.putInt("size", (int) new File(path).length());

        return image;
    }

    private WritableMap getImage(Activity activity, Uri uri, boolean resolvePath) throws Exception {
        WritableMap image = new WritableNativeMap();
        String path = uri.getPath();

        if (resolvePath) {
            path = RealPathUtil.getRealPathFromURI(activity, uri);
        }

        if (path == null || path.isEmpty()) {
            throw new Exception("Cannot resolve image path.");
        }

        if (path.startsWith("http://") || path.startsWith("https://")) {
            throw new Exception("Cannot select remote files");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BitmapFactory.decodeFile(path, options);

        if (options.outMimeType == null || options.outWidth == 0 || options.outHeight == 0) {
            throw new Exception("Invalid image selected");
        }

        image.putString("path", "file://" + path);
        image.putInt("width", options.outWidth);
        image.putInt("height", options.outHeight);
        image.putString("mime", options.outMimeType);
        image.putInt("size", (int) new File(path).length());

        if (includeBase64) {
            image.putString("data", getBase64StringFromFile(path));
        }

        return image;
    }

    public void startCropping(Activity activity, Uri uri) {
        UCrop.Options options = new UCrop.Options();
        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
        options.setOvalDimmedLayer(circular);

        UCrop.of(uri, Uri.fromFile(new File(this.getTmpDir(), UUID.randomUUID().toString() + ".jpg")))
                .withMaxResultSize(width, height)
                .withAspectRatio(width, height)
                .withOptions(options)
                .start(activity);
    }

    public void imagePickerResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (mPickerPromise == null) {
            return;
        }

        if (resultCode == Activity.RESULT_CANCELED) {
            mPickerPromise.reject(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        } else if (resultCode == Activity.RESULT_OK) {
            if (multiple) {
                ClipData clipData = data.getClipData();
                WritableArray result = new WritableNativeArray();

                try {
                    // only one image selected
                    if (clipData == null) {
                        result.pushMap(getSelection(activity, data.getData()));
                    } else {
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            result.pushMap(getSelection(activity, clipData.getItemAt(i).getUri()));
                        }
                    }

                    mPickerPromise.resolve(result);
                } catch (Exception ex) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }

            } else {
                Uri uri = data.getData();

                if (uri == null) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, "Cannot resolve image url");
                }

                if (cropping) {
                    startCropping(activity, uri);
                } else {
                    try {
                        mPickerPromise.resolve(getSelection(activity, uri));
                    } catch (Exception ex) {
                        mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                    }
                }
            }
        }
    }

    public void cameraPickerResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (mPickerPromise == null) {
            return;
        }

        if (resultCode == Activity.RESULT_CANCELED) {
            mPickerPromise.reject(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        } else if (resultCode == Activity.RESULT_OK) {
            Uri uri = mCameraCaptureURI;

            if (uri == null) {
                mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, "Cannot resolve image url");
                return;
            }

            if (cropping) {
                UCrop.Options options = new UCrop.Options();
                options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
                startCropping(activity, uri);
            } else {
                try {
                    mPickerPromise.resolve(getImage(activity, uri, true));
                } catch (Exception ex) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }
            }
        }
    }

    public void croppingResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (data != null) {
            final Uri resultUri = UCrop.getOutput(data);
            if (resultUri != null) {
                try {
                    mPickerPromise.resolve(getImage(activity, resultUri, false));
                } catch (Exception ex) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }
            } else {
                mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, "Cannot find image data");
            }
        } else {
            mPickerPromise.reject(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        }
    }

    @Override
    public void onActivityResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == IMAGE_PICKER_REQUEST) {
            imagePickerResult(activity, requestCode, resultCode, data);
        } else if (requestCode == CAMERA_PICKER_REQUEST) {
            cameraPickerResult(activity, requestCode, resultCode, data);
        } else if (requestCode == UCrop.REQUEST_CROP) {
            croppingResult(activity, requestCode, resultCode, data);
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
    }

    private boolean isCameraAvailable() {
        return mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
                || mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private File createNewFile(final boolean forcePictureDirectory) {
        String filename = "image-" + UUID.randomUUID().toString() + ".jpg";
        if (tmpImage && (!forcePictureDirectory)) {
            return new File(this.getTmpDir(), filename);
        } else {
            File path = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES);
            File f = new File(path, filename);

            try {
                path.mkdirs();
                f.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return f;
        }
    }

}
