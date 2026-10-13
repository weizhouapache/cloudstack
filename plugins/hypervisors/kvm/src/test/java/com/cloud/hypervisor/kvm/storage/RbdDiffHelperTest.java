// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.storage;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class RbdDiffHelperTest {

    @Mock
    KVMStoragePool poolMock;

    @Test
    public void qualifiedImageTestBareVolumePathIsQualifiedWithTheCephPool() {
        // The CLI is driven without a -p argument, so a bare path would resolve against the
        // default "rbd" pool and every call would quietly act on the wrong image, or on none.
        Mockito.doReturn("cs-pri2").when(poolMock).getSourceDir();

        Assert.assertEquals("cs-pri2/09dccf51-e5a1-4809-8e20-844548260c33",
                RbdDiffHelper.qualifiedImage(poolMock, "09dccf51-e5a1-4809-8e20-844548260c33"));
    }

    @Test
    public void qualifiedImageTestAlreadyQualifiedPathIsLeftAlone() {
        Assert.assertEquals("other-pool/image",
                RbdDiffHelper.qualifiedImage(poolMock, "other-pool/image"));
    }

    @Test
    public void qualifiedImageTestNullPathStaysNull() {
        Assert.assertNull(RbdDiffHelper.qualifiedImage(poolMock, null));
    }
}
