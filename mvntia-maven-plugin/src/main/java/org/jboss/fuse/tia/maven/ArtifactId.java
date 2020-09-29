/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.fuse.tia.maven;

import java.util.Collection;
import java.util.HashSet;

import org.apache.maven.artifact.Artifact;
import org.codehaus.plexus.util.SelectorUtils;

public class ArtifactId {

    private final String groupId;

    private final String artifactId;

    private final String type;

    private final String classifier;

    ArtifactId(Artifact artifact) {
        this(artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), artifact.getClassifier());
    }

    ArtifactId(String groupId, String artifactId, String type, String classifier) {
        this.groupId = (groupId != null) ? groupId : "";
        this.artifactId = (artifactId != null) ? artifactId : "";
        this.type = (type != null) ? type : "";
        this.classifier = (classifier != null) ? classifier : "";
    }

    ArtifactId(String id) {
        String[] tokens = new String[0];
        if (id != null && id.length() > 0) {
            tokens = id.split(":", -1);
        }
        groupId = (tokens.length > 0) ? tokens[0] : "";
        artifactId = (tokens.length > 1) ? tokens[1] : "*";
        type = (tokens.length > 3) ? tokens[2] : "*";
        classifier = (tokens.length > 3) ? tokens[3] : ((tokens.length > 2) ? tokens[2] : "*");
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getType() {
        return type;
    }

    public String getClassifier() {
        return classifier;
    }

    public boolean matches(ArtifactId pattern) {
        if (pattern == null) {
            return false;
        }
        if (!match(getGroupId(), pattern.getGroupId())) {
            return false;
        }
        if (!match(getArtifactId(), pattern.getArtifactId())) {
            return false;
        }
        if (!match(getType(), pattern.getType())) {
            return false;
        }
        if (!match(getClassifier(), pattern.getClassifier())) {
            return false;
        }
        return true;
    }

    private boolean match(String str, String pattern) {
        return SelectorUtils.match(pattern, str);
    }

    public static Collection<ArtifactId> toIds(Collection<String> patterns) {
        Collection<ArtifactId> result = new HashSet<>();
        if (patterns != null) {
            for (String pattern : patterns) {
                result.add(new ArtifactId(pattern));
            }
        }
        return result;
    }

    public static boolean matches(Collection<ArtifactId> patterns, Artifact artifact) {
        ArtifactId id = new ArtifactId(artifact);
        for (ArtifactId pattern : patterns) {
            if (id.matches(pattern)) {
                return true;
            }
        }
        return false;
    }

}
