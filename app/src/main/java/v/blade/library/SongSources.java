package v.blade.library;

public class SongSources {
    public void addSource(SongSource toAdd) {
        if (sources[0] != null && sources[0].getSource() == toAdd.getSource()
                || sources[1] != null && sources[1].getSource() == toAdd.getSource()
                || sources[2] != null && sources[2].getSource() == toAdd.getSource()) return;

        if (sources[0] == null) {
            sources[0] = toAdd;
        } else if (sources[0].getSource().getPriority() < toAdd.getSource().getPriority()) {
            sources[2] = sources[1];
            sources[1] = sources[0];
            sources[0] = toAdd;
        } else if (sources[1] == null) sources[1] = toAdd;
        else if (sources[1].getSource().getPriority() < toAdd.getSource().getPriority()) {
            sources[2] = sources[1];
            sources[1] = toAdd;
        } else if (sources[2] == null) sources[2] = toAdd;
        else System.err.println("Warning : incorrect call to addSource().");
    }

    public SongSource sources[] = new SongSource[Source.SOURCES.length];

    public void removeSource(SongSource s) {
        for (int i = 0; i < Source.SOURCES.length; i++) {
            if (sources[i] == s) {
                sources[i] = null;
                System.arraycopy(sources, i + 1, sources, i, Source.SOURCES.length - 1 - i);
                break;
            }
        }
    }

    public SongSource getSourceByPriority(int priority) {
        return sources[priority];
    }

    public SongSource getSourceByAbsolutePriority(int priority) {
        Source toMatch = Source.SOURCES[priority];
        for (SongSource s : sources) if (s != null) if (s.getSource() == toMatch) return s;
        return null;
    }

    public SongSource getSpotify() {
        for (SongSource s : sources)
            if (s != null && s.getSource() == Source.SOURCE_SPOTIFY) return s;
        return null;
    }

    public SongSource getDeezer() {
        for (SongSource s : sources)
            if (s != null && s.getSource() == Source.SOURCE_DEEZER) return s;
        return null;
    }

    public SongSource getLocal() {
        for (SongSource s : sources)
            if (s != null && s.getSource() == Source.SOURCE_LOCAL_LIB) return s;
        return null;
    }

    public static class SongSource {
        private Object id;
        private Source source;
        private boolean library;

        public SongSource(Object id, Source s) {
            this.id = id;
            this.source = s;
        }

        public Object getId() {
            return this.id;
        }

        public Source getSource() {
            return this.source;
        }

        public boolean getLibrary() {
            return this.library;
        }

        public void setLibrary(boolean lib) {
            this.library = lib;
        }
    }
}
