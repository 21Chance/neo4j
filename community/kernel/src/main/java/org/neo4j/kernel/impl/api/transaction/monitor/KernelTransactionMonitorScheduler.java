/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.kernel.impl.api.transaction.monitor;

import java.util.concurrent.TimeUnit;

import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.scheduler.JobScheduler;

public class KernelTransactionMonitorScheduler extends LifecycleAdapter
{
    private final KernelTransactionMonitor kernelTransactionMonitor;
    private final JobScheduler scheduler;
    private final long checkIntervalMillis;
    private JobHandle monitorJobHandle;

    public KernelTransactionMonitorScheduler( KernelTransactionMonitor kernelTransactionMonitor,
            JobScheduler scheduler, long checkIntervalMillis )
    {
        this.kernelTransactionMonitor = kernelTransactionMonitor;
        this.scheduler = scheduler;
        this.checkIntervalMillis = checkIntervalMillis;
    }

    @Override
    public void start()
    {
        if ( checkIntervalMillis > 0 )
        {
            monitorJobHandle = scheduler.scheduleRecurring( Group.TRANSACTION_TIMEOUT_MONITOR, kernelTransactionMonitor,
                    checkIntervalMillis, TimeUnit.MILLISECONDS );
        }
    }

    @Override
    public void stop()
    {
        if ( monitorJobHandle != null )
        {
            monitorJobHandle.cancel();
        }
    }
}
