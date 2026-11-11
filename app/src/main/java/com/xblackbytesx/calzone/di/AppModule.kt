package com.xblackbytesx.calzone.di

import android.content.Context
import com.xblackbytesx.calzone.account.KeystoreCipher
import com.xblackbytesx.calzone.account.Session
import com.xblackbytesx.calzone.bridge.Bridges
import com.xblackbytesx.calzone.bridge.CalendarBridge
import com.xblackbytesx.calzone.bridge.ContactsBridge
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.prefs.PrefsStore
import com.xblackbytesx.calzone.reminders.Reminders
import com.xblackbytesx.calzone.sync.SyncRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** The engine's store: one SQLite file in the app's own files, never backed up. */
    @Provides
    @Singleton
    fun core(@ApplicationContext context: Context): Core = Core(File(context.filesDir, "calzone.db").absolutePath)

    @Provides
    @Singleton
    fun session(@ApplicationContext context: Context): Session = Session(context, KeystoreCipher())

    @Provides
    @Singleton
    fun bridges(@ApplicationContext context: Context, core: Core): Bridges =
        Bridges(core, CalendarBridge(context, core), ContactsBridge(context, core))

    @Provides
    @Singleton
    fun runner(@ApplicationContext context: Context, core: Core, session: Session, bridges: Bridges, prefs: PrefsStore, reminders: Reminders): SyncRunner =
        SyncRunner(context, core, session, bridges, prefs, reminders)

    @Provides
    @Singleton
    fun reminders(@ApplicationContext context: Context, core: Core, prefs: PrefsStore): Reminders = Reminders(context, core, prefs)

    @Provides
    @Singleton
    fun prefs(@ApplicationContext context: Context): PrefsStore = PrefsStore(context)
}
