/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.scm.pipeline;

import org.apache.hadoop.hdds.scm.exceptions.SCMException;

/**
 * Signals that a pipeline state is not recognized.
 */
public class UnknownPipelineStateException extends SCMException {
  /**
   * Constructs an {@code UnknownPipelineStateException} with {@code null}
   * as its error detail message.
   */
  public UnknownPipelineStateException() {
    super(ResultCodes.UNKNOWN_PIPELINE_STATE);
  }

  /**
   * Constructs an {@code UnknownPipelineStateException} with the specified
   * detail message.
   *
   * @param message
   *        The detail message (which is saved for later retrieval
   *        by the {@link #getMessage()} method)
   */
  public UnknownPipelineStateException(String message) {
    super(message, ResultCodes.UNKNOWN_PIPELINE_STATE);
  }
}
