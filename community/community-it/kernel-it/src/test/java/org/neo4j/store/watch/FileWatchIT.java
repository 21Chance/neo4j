/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.store.watch;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.WatchKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.Settings;
import org.neo4j.dbms.database.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.io.fs.watcher.FileWatchEventListener;
import org.neo4j.io.fs.watcher.FileWatcher;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointer;
import org.neo4j.kernel.impl.transaction.log.checkpoint.SimpleTriggerInfo;
import org.neo4j.kernel.impl.transaction.log.files.TransactionLogFilesHelper;
import org.neo4j.kernel.impl.util.watcher.DefaultFileDeletionEventListener;
import org.neo4j.kernel.impl.util.watcher.FileSystemWatcherService;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static java.time.Duration.ofMinutes;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

@ExtendWith( TestDirectoryExtension.class )
class FileWatchIT
{
    @Inject
    private TestDirectory testDirectory;

    private AssertableLogProvider logProvider;
    private GraphDatabaseService database;
    private DatabaseLayout databaseLayout;

    @BeforeEach
    void setUp()
    {
        File customStoreRoot = testDirectory.storeDir( "customStore" );
        databaseLayout = testDirectory.databaseLayout( customStoreRoot );
        logProvider = new AssertableLogProvider();
        database = new TestGraphDatabaseFactory().setInternalLogProvider( logProvider ).newEmbeddedDatabase( databaseLayout.databaseDirectory() );
    }

    @AfterEach
    void tearDown()
    {
        shutdownDatabaseSilently( database );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void notifyAboutStoreFileDeletion()
    {
        assertTimeoutPreemptively( ofMinutes( 1 ), () ->
        {
            String fileName = databaseLayout.metadataStore().getName();
            FileWatcher fileWatcher = getFileWatcher( database );
            CheckPointer checkpointer = getCheckpointer( database );
            DeletionLatchEventListener deletionListener = new DeletionLatchEventListener( fileName );
            fileWatcher.addFileWatchEventListener( deletionListener );

            do
            {
                createNode( database );
                forceCheckpoint( checkpointer );
            }
            while ( !deletionListener.awaitModificationNotification() );

            deleteFile( databaseLayout.databaseDirectory(), fileName );
            deletionListener.awaitDeletionNotification();

            logProvider.assertLogStringContains( "'" + fileName + "' which belongs to the '" + databaseLayout.databaseDirectory().getName() +
                    "' database was deleted while it was running." );
        } );
    }

    @Test
    void notifyWhenFileWatchingFailToStart()
    {
        assertTimeoutPreemptively( ofMinutes( 1 ), () ->
        {
            AssertableLogProvider logProvider = new AssertableLogProvider( true );
            GraphDatabaseService db = null;
            try
            {
                db = new TestGraphDatabaseFactory().setInternalLogProvider( logProvider )
                        .setFileSystem( new NonWatchableFileSystemAbstraction() )
                        .newEmbeddedDatabase( testDirectory.storeDir( "failed-start-db" ) );

                logProvider.assertContainsMessageContaining(
                        "Can not create file watcher for current file system. " + "File monitoring capabilities for store files will be disabled." );
            }
            finally
            {
                shutdownDatabaseSilently( db );
            }
        } );
    }

    @Test
    void doNotNotifyAboutLuceneIndexFilesDeletion()
    {
        assertTimeoutPreemptively( ofMinutes( 1 ), () ->
        {
            DependencyResolver dependencyResolver = ((GraphDatabaseAPI) database).getDependencyResolver();
            FileWatcher fileWatcher = getFileWatcher( database );
            CheckPointer checkPointer = dependencyResolver.resolveDependency( CheckPointer.class );

            String propertyStoreName = databaseLayout.propertyStore().getName();
            AccumulativeDeletionEventListener accumulativeListener = new AccumulativeDeletionEventListener();
            ModificationEventListener modificationListener = new ModificationEventListener( propertyStoreName );
            fileWatcher.addFileWatchEventListener( modificationListener );
            fileWatcher.addFileWatchEventListener( accumulativeListener );

            String labelName = "labelName";
            String propertyName = "propertyName";
            Label testLabel = Label.label( labelName );
            createIndexes( database, propertyName, testLabel );
            do
            {
                createNode( database, propertyName, testLabel );
                forceCheckpoint( checkPointer );
            }
            while ( !modificationListener.awaitModificationNotification() );

            fileWatcher.removeFileWatchEventListener( modificationListener );
            ModificationEventListener afterRemovalListener = new ModificationEventListener( propertyStoreName );
            fileWatcher.addFileWatchEventListener( afterRemovalListener );

            dropAllIndexes( database );
            do
            {
                createNode( database, propertyName, testLabel );
                forceCheckpoint( checkPointer );
            }
            while ( !afterRemovalListener.awaitModificationNotification() );

            accumulativeListener.assertDoesNotHaveAnyDeletions();
        } );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void doNotMonitorTransactionLogFiles()
    {
        assertTimeoutPreemptively( ofMinutes( 1 ), () ->
        {
            FileWatcher fileWatcher = getFileWatcher( database );
            CheckPointer checkpointer = getCheckpointer( database );
            String metadataStore = databaseLayout.metadataStore().getName();
            ModificationEventListener modificationEventListener = new ModificationEventListener( metadataStore );
            fileWatcher.addFileWatchEventListener( modificationEventListener );

            do
            {
                createNode( database );
                forceCheckpoint( checkpointer );
            }
            while ( !modificationEventListener.awaitModificationNotification() );

            String fileName = TransactionLogFilesHelper.DEFAULT_NAME + ".0";
            DeletionLatchEventListener deletionListener = new DeletionLatchEventListener( fileName );
            fileWatcher.addFileWatchEventListener( deletionListener );
            deleteFile( databaseLayout.getTransactionLogsDirectory(), fileName );
            deletionListener.awaitDeletionNotification();

            AssertableLogProvider.LogMatcher logMatcher =
                    AssertableLogProvider.inLog( DefaultFileDeletionEventListener.class ).info( containsString( fileName ) );
            logProvider.assertNone( logMatcher );
        } );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void notifyWhenWholeStoreDirectoryRemoved()
    {
        assertTimeoutPreemptively( ofMinutes( 1 ), () ->
        {
            String fileName = databaseLayout.metadataStore().getName();
            FileWatcher fileWatcher = getFileWatcher( database );
            CheckPointer checkpointer = getCheckpointer( database );

            ModificationEventListener modificationListener = new ModificationEventListener( fileName );
            fileWatcher.addFileWatchEventListener( modificationListener );
            do
            {
                createNode( database );
                forceCheckpoint( checkpointer );
            }
            while ( !modificationListener.awaitModificationNotification() );
            fileWatcher.removeFileWatchEventListener( modificationListener );

            String storeDirectoryName = databaseLayout.databaseDirectory().getName();
            DeletionLatchEventListener eventListener = new DeletionLatchEventListener( storeDirectoryName );
            fileWatcher.addFileWatchEventListener( eventListener );
            FileUtils.deleteRecursively( databaseLayout.databaseDirectory() );

            eventListener.awaitDeletionNotification();

            logProvider.assertLogStringContains( "'" + storeDirectoryName + "' which belongs to the '" +
                    databaseLayout.databaseDirectory().getName() + "' database was deleted while it was running." );
        } );
    }

    @Test
    void shouldLogWhenDisabled()
    {
        assertTimeoutPreemptively( ofMinutes( 1 ), () ->
        {
            AssertableLogProvider logProvider = new AssertableLogProvider( true );
            GraphDatabaseService db = null;
            try
            {
                DatabaseManagementService managementService = new TestGraphDatabaseFactory().setInternalLogProvider( logProvider )
                                .setFileSystem( new NonWatchableFileSystemAbstraction() )
                                .newEmbeddedDatabaseBuilder( testDirectory.databaseLayout( "failed-start-db" ).databaseDirectory() )
                                .setConfig( GraphDatabaseSettings.filewatcher_enabled, Settings.FALSE ).newDatabaseManagementService();
                db = managementService.database( DEFAULT_DATABASE_NAME );

                logProvider.assertContainsMessageContaining( "File watcher disabled by configuration." );
            }
            finally
            {
                shutdownDatabaseSilently( db );
            }
        } );
    }

    private static void shutdownDatabaseSilently( GraphDatabaseService databaseService )
    {
        if ( databaseService != null )
        {
            try
            {
                databaseService.shutdown();
            }
            catch ( Exception expected )
            {
                // ignored
            }
        }
    }

    private static void dropAllIndexes( GraphDatabaseService database )
    {
        try ( Transaction transaction = database.beginTx() )
        {
            for ( IndexDefinition definition : database.schema().getIndexes() )
            {
                definition.drop();
            }
            transaction.success();
        }
    }

    private static void createIndexes( GraphDatabaseService database, String propertyName, Label testLabel )
    {
        try ( Transaction transaction = database.beginTx() )
        {
            database.schema().indexFor( testLabel ).on( propertyName ).create();
            transaction.success();
        }

        try ( Transaction ignored = database.beginTx() )
        {
            database.schema().awaitIndexesOnline( 1, TimeUnit.MINUTES );
        }
    }

    private static void forceCheckpoint( CheckPointer checkPointer ) throws IOException
    {
        checkPointer.forceCheckPoint( new SimpleTriggerInfo( "testForceCheckPoint" ) );
    }

    private static void createNode( GraphDatabaseService database, String propertyName, Label testLabel )
    {
        try ( Transaction transaction = database.beginTx() )
        {
            Node node = database.createNode( testLabel );
            node.setProperty( propertyName, "value" );
            transaction.success();
        }
    }

    private static CheckPointer getCheckpointer( GraphDatabaseService database )
    {
        return ((GraphDatabaseAPI) database).getDependencyResolver().resolveDependency( CheckPointer.class );
    }

    private static FileWatcher getFileWatcher( GraphDatabaseService database )
    {
        DependencyResolver dependencyResolver = ((GraphDatabaseAPI) database).getDependencyResolver();
        return dependencyResolver.resolveDependency( FileSystemWatcherService.class ).getFileWatcher();
    }

    private static void deleteFile( File storeDir, String fileName )
    {
        File metadataStore = new File( storeDir, fileName );
        FileUtils.deleteFile( metadataStore );
    }

    private static void createNode( GraphDatabaseService database )
    {
        try ( Transaction transaction = database.beginTx() )
        {
            database.createNode();
            transaction.success();
        }
    }

    private static class NonWatchableFileSystemAbstraction extends DefaultFileSystemAbstraction
    {
        @Override
        public FileWatcher fileWatcher() throws IOException
        {
            throw new IOException( "You can't watch me!" );
        }
    }

    private static class AccumulativeDeletionEventListener implements FileWatchEventListener
    {
        private final List<String> deletedFiles = new ArrayList<>();

        @Override
        public void fileDeleted( WatchKey key, String fileName )
        {
            deletedFiles.add( fileName );
        }

        void assertDoesNotHaveAnyDeletions()
        {
            assertThat( "Should not have any deletions registered", deletedFiles, Matchers.empty() );
        }
    }

    private static class ModificationEventListener implements FileWatchEventListener
    {
        final String expectedFileName;
        private final CountDownLatch modificationLatch = new CountDownLatch( 1 );

        ModificationEventListener( String expectedFileName )
        {
            this.expectedFileName = expectedFileName;
        }

        @Override
        public void fileModified( WatchKey key, String fileName )
        {
            if ( expectedFileName.equals( fileName ) )
            {
                modificationLatch.countDown();
            }
        }

        boolean awaitModificationNotification() throws InterruptedException
        {
            return modificationLatch.await(1, TimeUnit.SECONDS);
        }
    }

    private static class DeletionLatchEventListener extends ModificationEventListener
    {
        private final CountDownLatch deletionLatch = new CountDownLatch( 1 );

        DeletionLatchEventListener( String expectedFileName )
        {
            super( expectedFileName );
        }

        @Override
        public void fileDeleted( WatchKey key, String fileName )
        {
            if ( fileName.endsWith( expectedFileName ) )
            {
                deletionLatch.countDown();
            }
        }

        void awaitDeletionNotification() throws InterruptedException
        {
            deletionLatch.await();
        }

    }
}
