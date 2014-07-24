/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.locationtech.geogig.rest.repository;

import org.locationtech.geogig.web.console.ConsoleResourceResource;
import org.restlet.Redirector;
import org.restlet.Router;

public class RepositoryRouter extends Router {

    public RepositoryRouter() {
        attach("/manifest", ManifestResource.class);
        attach("/objects/{id}", new ObjectFinder());
        attach("/batchobjects", new BatchedObjectResource());
        attach("/sendobject", SendObjectResource.class);
        attach("/exists", ObjectExistsResource.class);
        attach("/beginpush", BeginPush.class);
        attach("/endpush", EndPush.class);
        attach("/getdepth", DepthResource.class);
        attach("/getparents", ParentResource.class);
        attach("/affectedfeatures", AffectedFeaturesResource.class);
        attach("/filteredchanges", new FilteredChangesResource());
        attach("/applychanges", new ApplyChangesResource());
        attach("/mergefeature", MergeFeatureResource.class);

        Redirector redirector = new Redirector(getContext(), "console/",
                Redirector.MODE_CLIENT_PERMANENT);
        attach("/console/{resource}", ConsoleResourceResource.class);
        attach("/console/", ConsoleResourceResource.class);
        attach("/console", redirector);
    }
}
