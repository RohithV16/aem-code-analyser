package com.probe;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;

interface ProbeAdapter { }

@Model(adaptables = Resource.class)
class UnusedProbeModel { }

@Model(adaptables = Resource.class, adapters = ProbeAdapter.class, resourceType = "probe/dup")
class DuplicateModelOne implements ProbeAdapter { }

@Model(adaptables = Resource.class, adapters = ProbeAdapter.class, resourceType = "probe/dup")
class DuplicateModelTwo implements ProbeAdapter { }
