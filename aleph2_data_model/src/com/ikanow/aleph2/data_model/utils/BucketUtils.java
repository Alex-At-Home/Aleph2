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

import java.util.Optional;

import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;

/**
 * Provides a set of util functions for DataBucketBean
 * 
 * @author Burch
 *
 */
public class BucketUtils {
	/**
	 * Returns a clone of the bean and modifies the full_name field to provide a
	 * test path instead (by prepending "/alphe2_testing/{user_id}" to the path).
	 * 
	 * @param original_bean
	 * @param user_id
	 * @return original_bean with the full_name field modified with a test path
	 */
	public static DataBucketBean convertDataBucketBeanToTest(final DataBucketBean original_bean, String user_id) {
		//TODO when creating a bucket do we need to block any attempt of
		//users to start with /test?
		final String new_full_name = "/aleph2_testing/" + user_id + "/" + original_bean.full_name();
		return BeanTemplateUtils.clone(original_bean)
				.with(DataBucketBean::full_name, new_full_name)
				.done();
	}
	
	///////////////////////////////////////////////////////////////////////////
	
	//TODO: create sub-bucket
	
	//////////////////////////////////////////////////////////////////////////

	// CREATE BUCKET SIGNATURE
	
	private static final int MAX_COLL_COMP_LEN = 8;
	
	/** Creates a reproducible, unique, but human readable signature for a bucket that is safe to use as a kafka topic/mongodb collection/elasticsearch index/etc
	 *  Generated by taking 1-3 directories from the path and then appening the end of a UUID
	 * @param path
	 * @return
	 */
	public static String getUniqueSignature(final String path, final Optional<String> subcollection) {
		
		String[] components = Optional.of(path)
								.map(p -> p.startsWith("/") ? p.substring(1) : p)
								.get()
								.split("[/]");
		
		if (1 == components.length) {
			return tidyUpIndexName(safeTruncate(components[0], MAX_COLL_COMP_LEN)
										+ addOptionalSubCollection(subcollection, MAX_COLL_COMP_LEN))
										+ "_" + generateUuidSuffix(path);
		}
		else if (2 == components.length) {
			return tidyUpIndexName(safeTruncate(components[0], MAX_COLL_COMP_LEN) 
									+ "_" + safeTruncate(components[1], MAX_COLL_COMP_LEN)
									+ addOptionalSubCollection(subcollection, MAX_COLL_COMP_LEN))
									+ "_" + generateUuidSuffix(path);
		}
		else { // take the first and the last 2
			final int n = components.length;
			return tidyUpIndexName(safeTruncate(components[0], MAX_COLL_COMP_LEN) 
									+ "_" + safeTruncate(components[n-2], MAX_COLL_COMP_LEN) 
									+ "_" + safeTruncate(components[n-1], MAX_COLL_COMP_LEN) 
									+ addOptionalSubCollection(subcollection, MAX_COLL_COMP_LEN))
									+ "_" + generateUuidSuffix(path);
		}
	}
	// Utils for getBaseIndexName
	private static String addOptionalSubCollection(final Optional<String> subcollection, final int max_len) {
		return subcollection.map(sc -> "_" + safeTruncate(sc, max_len)).orElse("");
	}
	private static String tidyUpIndexName(final String in) {
		return in.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("__+", "_");
	}
	private static String generateUuidSuffix(final String in) {
		return UuidUtils.get().getContentBasedUuid(in.getBytes()).substring(24);
	}
	private static String safeTruncate(final String in, final int max_len) {
		return in.length() < max_len ? in : in.substring(0, max_len);
	}
	
}
