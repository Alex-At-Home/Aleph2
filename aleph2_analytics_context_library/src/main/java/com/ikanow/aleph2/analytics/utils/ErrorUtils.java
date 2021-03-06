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
 *******************************************************************************/
package com.ikanow.aleph2.analytics.utils;

/** Error messages for analytics context
 * @author Alex
 */
public class ErrorUtils extends com.ikanow.aleph2.data_model.utils.ErrorUtils {

	final public static String NOT_SUPPORTED_IN_STREAM_ANALYICS = "Functionality does not apply to streaming context - this is for batch";
	final public static String NOT_SUPPORTED_IN_BATCH_ANALYICS = "Functionality does not apply to batch context - this is for streaming";
	final public static String SERVICE_RESTRICTIONS = "Can't call getAnalyticsContextSignature/getAnalyticsContextLibraries with different 'services' parameter; can't call getUnderlyingArtefacts without having called getAnalyticsContextSignature/getAnalyticsContextLibraries; can't call getServiceInput/Output after getAnalyticsContextSignature/getAnalyticsContextLibraries.";
	final public static String TECHNOLOGY_NOT_MODULE = "Can only be called from technology, not module";
	final public static String MODULE_NOT_TECHNOLOGY = "Can only be called from module, not technology";
	final public static String BUCKET_NOT_FOUND_OR_NOT_READABLE = "Bucket {0} does not exist or is not readable";
	final public static String INPUT_PATH_NOT_A_TRANSIENT_BATCH = "Bucket {0} job {1} pointing to bucket {2} job {3}: not a batch transient_output (or bucket doesn't exist/not readable)";
	final public static String HIGH_GRANULARITY_FILTER_NOT_SUPPORTED = "Bucket {0} job {1} input {2}: requested high granularity filter on input paths, not supported";
	final public static String BREAK_DEDUPLICATION_POLICY = "In very_strict mode, trying to emit > 1 object; in strict mode, trying to output a new _id with an existing grouping_key";
	final public static String INVALID_LOOKUP_SERVICE = "Requested lookup service {0} but it does not exist";
	final public static String MISSING_DOCUMENT_SERVICE = "Buckets without a manual lookup override (schema, and data service in the schema) must have an active document schema";
}
