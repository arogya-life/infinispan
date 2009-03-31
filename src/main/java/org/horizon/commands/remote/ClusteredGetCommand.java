/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2000 - 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.horizon.commands.remote;

import org.horizon.CacheException;
import org.horizon.commands.CacheRpcCommand;
import org.horizon.commands.DataCommand;
import org.horizon.container.DataContainer;
import org.horizon.container.entries.InternalCacheEntry;
import org.horizon.context.InvocationContext;
import org.horizon.loader.CacheLoaderManager;
import org.horizon.logging.Log;
import org.horizon.logging.LogFactory;

/**
 * Issues a clustered get call, for use primarily by the {@link ClusteredCacheLoader}.  This is not a {@link
 * org.horizon.commands.VisitableCommand} and hence not passed up the {@link org.horizon.interceptors.base.CommandInterceptor}
 * chain.
 * <p/>
 *
 * @author Mircea.Markus@jboss.com
 * @since 4.0
 */
public class ClusteredGetCommand implements CacheRpcCommand
{

   public static final byte COMMAND_ID = 22;
   private static final Log log = LogFactory.getLog(ClusteredGetCommand.class);
   private static final boolean trace = log.isTraceEnabled();

   private Object key;
   private String cacheName;

   private DataContainer dataContainer;
   private CacheLoaderManager cacheLoaderManager;

   public ClusteredGetCommand() {
   }

   public ClusteredGetCommand(Object key, String cacheName) {
      this.key = key;
      this.cacheName = cacheName;
   }

   public void initialize(DataContainer dataContainer, CacheLoaderManager clManager) {
      this.dataContainer = dataContainer;
      this.cacheLoaderManager = clManager;
   }

   /**
    * Invokes a {@link DataCommand} on a remote cache and returns results.
    *
    * @param context invocation context, ignored.
    * @return a List containing 2 elements: a boolean, (true or false) and a value (Object) which is the result of
    *         invoking a remote get specified by {@link #getDataCommand()}.
    */
   public Object perform(InvocationContext context) throws Throwable {
      if (key != null) {
         InternalCacheEntry cacheEntry = dataContainer.get(key);
         if (cacheEntry == null) {
            context.setOriginLocal(false); //to make sure that if there is an ClusteredCl, this won't initiate a remote
            // lookup
            if (cacheLoaderManager != null && cacheLoaderManager.getCacheLoader() != null)
               cacheEntry = cacheLoaderManager.getCacheLoader().load(key);
         }
         return cacheEntry;
      } else {
         throw new CacheException("Invalid command. Missing key!");
      }
   }

   public byte getCommandId() {
      return COMMAND_ID;
   }

   public Object[] getParameters() {
      return new Object[]{key, cacheName};
   }

   public void setParameters(int commandId, Object[] args) {
      key = args[0];
      cacheName = (String) args[1];
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ClusteredGetCommand that = (ClusteredGetCommand) o;

      return !(key != null ? !key.equals(that.key) : that.key != null);
   }

   @Override
   public int hashCode() {
      int result;
      result = (key != null ? key.hashCode() : 0);
      return result;
   }

   @Override
   public String toString() {
      return "ClusteredGetCommand{" +
            "key=" + key +
            ", dataContainer=" + dataContainer +
            '}';
   }

   public String getCacheName() {
      return cacheName;
   }
}
