/*
  Copyright (c) 2020 Robert Bosch GmbH. All Rights Reserved.

  SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.aries.api.connection;

public enum ConnectionState {
    init,
    invitation,
    request,
    response,
    active,
    error,
    inactive
}
