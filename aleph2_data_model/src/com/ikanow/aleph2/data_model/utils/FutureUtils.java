/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.ikanow.aleph2.data_model.utils;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.checkerframework.checker.nullness.qual.NonNull;

import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
 
/** Utilities relating to the specific use of futures within Aleph2
 * @author acp
 *
 */
public class FutureUtils {

	/** Wraps a scala Future in a completable future 
	 * @param f the scala Future
	 * @return the CompletableFuture
	 */
	@SuppressWarnings("unchecked")
	@NonNull
	public static <T> CompletableFuture<T> wrap(final scala.concurrent.Future<Object> f) {
		// Note the beginnings of a better way are here: http://onoffswitch.net/converting-akka-scala-futures-java-futures/
		// but needs completing (basically: register a callback with the scala future, when that completes call complete/completeExceptionally)
		// In the meantime this works but clogs up one of the threads from the common pool
		
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (f.isCompleted()) {
					return (T)f.value().get().get();
				}
				else {
					try {
						return (T) Await.result(f, Duration.Inf());
					} catch (Exception e) {
						throw new ExecutionException(e);
					}
				}
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}
	
	/** Creates a trivial management future with no side channel - ie equivalent to the input future itself
	 * @return the management future (just containing the base future)
	 */
	@NonNull
	public static <T> ManagementFuture<T> createManagementFuture(final @NonNull CompletableFuture<T> base_future) {
		return new ManagementFuture<T>(base_future, Optional.empty());
	}
	
	/** Creates a trivial management future with the specified side channel - the future interface describes the primary action, the future returned by getManagementResults describes secondary results generated by actions spawned by the primary actions
	 * @return the management future (just containing the base future)
	 */
	@NonNull
	public static <T> ManagementFuture<T> createManagementFuture(final @NonNull CompletableFuture<T> base_future, 
															final @NonNull CompletableFuture<Collection<BasicMessageBean>> secondary_results)
	{
		return new ManagementFuture<T>(base_future, Optional.of(secondary_results));
	}
	
	/** A wrapper for a future that enables decoration with a management side channel
	 * @author acp
	 *
	 * @param <T> - the contents of the original wrapper
	 */
	public static class ManagementFuture<T> extends CompletableFuture<T> {

		/** User constructor
		 * @param delegate - the result of the primary action
		 * @param side_channel - a ist of message beans generated by the management systems while perform the primary action
		 */
		protected ManagementFuture(final @NonNull CompletableFuture<T> delegate, final @NonNull Optional<CompletableFuture<Collection<BasicMessageBean>>> side_channel) {
			_delegate = delegate.whenComplete((value, exception) -> {
				if (null != exception) this.completeExceptionally(exception);
				else this.complete(value);
			});
			_management_side_channel = side_channel;
		}
		
		protected final CompletableFuture<T> _delegate;
		protected final Optional<CompletableFuture<Collection<BasicMessageBean>>> _management_side_channel;
		
		/* (non-Javadoc)
		 * @see java.util.concurrent.Future#cancel(boolean)
		 */
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return _delegate.cancel(mayInterruptIfRunning);
		}

		/* (non-Javadoc)
		 * @see java.util.concurrent.Future#isCancelled()
		 */
		@Override
		public boolean isCancelled() {
			return _delegate.isCancelled();
		}

		/** If this future has a management side channel, returns that (otherwise an immediately completable future to an empty list)
		 * @return the management side channel future
		 */
		public @NonNull CompletableFuture<Collection<BasicMessageBean>> getManagementResults() {
			return _management_side_channel.orElse(CompletableFuture.completedFuture(Collections.emptyList()));
		}		
	}

	/** Generates a future that will error as soon as it's touched
	 * @param e - the underlying exception
	 * @return a future that errors when touched
	 */
	@NonNull
	public static <T> CompletableFuture<T> returnError(final @NonNull Exception e) {
		return new CompletableFuture<T>() {
			{
				this.completeExceptionally(e);
			}
		};		
	}	
}
