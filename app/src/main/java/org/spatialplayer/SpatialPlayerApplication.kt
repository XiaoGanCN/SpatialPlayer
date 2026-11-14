package org.spatialplayer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.spatialplayer.ui.HighRefreshRate

class SpatialPlayerApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {super.onCreate();registerActivityLifecycleCallbacks(this)}
    override fun onActivityCreated(activity:Activity,state:Bundle?) {HighRefreshRate.apply(activity.window)}
    override fun onActivityResumed(activity:Activity) {HighRefreshRate.apply(activity.window)}
    override fun onActivityStarted(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivityStopped(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
    override fun onActivityDestroyed(activity:Activity)=Unit
}
