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

package org.apache.hadoop.ozone.security;

import java.util.Collection;
import org.apache.hadoop.hdds.annotation.InterfaceAudience;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.security.token.delegation.AbstractDelegationTokenSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A delegation token selector that is specialized for Ozone.
 */
@InterfaceAudience.Private
public class OzoneDelegationTokenSelector
    extends AbstractDelegationTokenSelector<OzoneTokenIdentifier> {

  private static final Logger LOG = LoggerFactory.getLogger(OzoneDelegationTokenSelector.class);

  public OzoneDelegationTokenSelector() {
    super(OzoneTokenIdentifier.KIND_NAME);
  }

  @Override
  public Token<OzoneTokenIdentifier> selectToken(Text service,
      Collection<Token<? extends TokenIdentifier>> tokens) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Getting token for service {}", service);
    }
    Token token = getSelectedTokens(service, tokens);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Got tokens: {} for service {}", token, service);
    }
    return token;
  }

  private Token<OzoneTokenIdentifier> getSelectedTokens(Text service,
      Collection<Token<? extends TokenIdentifier>> tokens) {
    if (service == null) {
      return null;
    }
    for (Token<? extends TokenIdentifier> token : tokens) {
      if (OzoneTokenIdentifier.KIND_NAME.equals(token.getKind())
          && token.getService().toString().contains(service.toString())) {
        return (Token<OzoneTokenIdentifier>) token;
      }
    }
    return null;
  }

}

