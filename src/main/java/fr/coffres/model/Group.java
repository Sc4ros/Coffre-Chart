package fr.coffres.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of friends, made once and chosen for as many projects as wanted. Its projects are seen only by its
 * creator and its members (and the admins). Lists are replaced, never changed in place.
 */
public final class Group {
    public String id;
    public String name;
    public String owner;
    public String ownerName;
    public long created;
    /** The other players of the group (the creator is not in this list). */
    public List<Member> members = new ArrayList<>();

    public static final class Member {
        public String uuid;
        public String name;

        public Member() {
        }

        public Member(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    /** Creator or member. */
    public boolean has(String uuid) {
        if (uuid == null) {
            return false;
        }
        if (uuid.equals(owner)) {
            return true;
        }
        for (Member m : members) {
            if (uuid.equals(m.uuid)) {
                return true;
            }
        }
        return false;
    }
}
