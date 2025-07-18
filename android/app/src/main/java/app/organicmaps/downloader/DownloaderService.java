package app.organicmaps.downloader;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.downloader.CountryItem;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.util.log.Logger;
import java.util.List;

public class DownloaderService extends Service implements MapManager.StorageCallback
{
  private static final String TAG = DownloaderService.class.getSimpleName();

  private final DownloaderNotifier mNotifier = new DownloaderNotifier(this);
  private int mSubscriptionSlot;

  @Override
  public void onCreate()
  {
    super.onCreate();

    Logger.i(TAG);

    mSubscriptionSlot = MapManager.nativeSubscribe(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    Logger.i(TAG, "Downloading: " + MapManager.nativeIsDownloading());

    var notification = mNotifier.buildProgressNotification();
    Logger.i(TAG, "Starting Downloader Foreground Service");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
      ServiceCompat.startForeground(this, DownloaderNotifier.NOTIFICATION_ID, notification,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else
      ServiceCompat.startForeground(this, DownloaderNotifier.NOTIFICATION_ID, notification, 0);

    return START_NOT_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  @Override
  public void onStatusChanged(List<MapManager.StorageCallbackData> data)
  {
    var isDownloading = MapManager.nativeIsDownloading();
    var hasFailed = hasDownloadFailed(data);

    Logger.i(TAG, "Downloading: " + isDownloading + " failure: " + hasFailed);

    if (!isDownloading)
    {
      if (hasFailed)
      {
        // Detach service from the notification to keep after the service is stopped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        {
          stopForeground(Service.STOP_FOREGROUND_DETACH);
        }
        else
        {
          stopForeground(false);
        }
      }
      stopSelf();
    }
  }

  @Override
  public void onProgress(String countryId, long localSize, long remoteSize)
  {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED)
    {
      Logger.w(TAG, "Permission POST_NOTIFICATIONS is not granted, skipping notification");
      return;
    }

    // TODO: How to calculate progress?
    mNotifier.notifyProgress();
  }

  @Override
  public void onDestroy()
  {
    super.onDestroy();

    Logger.i(TAG, "onDestroy");

    MapManager.nativeUnsubscribe(mSubscriptionSlot);
  }

  /**
   * Start the foreground service to keep the user informed about the status of region downloads.
   */
  public static void startForegroundService()
  {
    Logger.i(TAG);
    var context = MwmApplication.sInstance;
    ContextCompat.startForegroundService(context, new Intent(context, DownloaderService.class));
  }

  private boolean hasDownloadFailed(List<MapManager.StorageCallbackData> data)
  {
    for (MapManager.StorageCallbackData item : data)
    {
      if (item.isLeafNode && item.newStatus == CountryItem.STATUS_FAILED)
      {
        if (MapManager.nativeIsAutoretryFailed())
        {
          mNotifier.notifyDownloadFailed(item.countryId);
          return true;
        }
      }
    }

    return false;
  }
}
