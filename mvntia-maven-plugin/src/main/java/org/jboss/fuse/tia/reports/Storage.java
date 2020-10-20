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
package org.jboss.fuse.tia.reports;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

public interface Storage {

    State getState() throws IOException;

    void removeNotes() throws IOException;

    void writeNotes(String notes) throws IOException;

    class State {
        public final String note;
        public final Set<String> modified;
        public final Set<String> uncommitted;

        public State(String note, Set<String> modified, Set<String> uncommitted) {
            this.note = note;
            this.modified = modified;
            this.uncommitted = uncommitted;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            State state = (State) o;
            return Objects.equals(note, state.note) &&
                    Objects.equals(modified, state.modified) &&
                    Objects.equals(uncommitted, state.uncommitted);
        }

        @Override
        public int hashCode() {
            return Objects.hash(note, modified, uncommitted);
        }

        @Override
        public String toString() {
            return "State{" +
                    "note='" + note + '\'' +
                    ", modified=" + modified +
                    ", uncommitted=" + uncommitted +
                    '}';
        }
    }
}
