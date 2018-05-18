package v.blade.library;

import v.blade.R;

public class SongSources
{
    public static class Source
    {
        private final int iconImage;
        private final int logoImage;
        private int priority;
        private boolean available;
        private String name;

        private Source(int iconImage, int logoImage, String name) {this.iconImage = iconImage; this.logoImage = logoImage; this.name = name;}
        public int getIconImage() {return iconImage;}
        public int getLogoImage() {return logoImage;}
        public int getPriority() {return priority;}
        public void setPriority(int priority) {this.priority = priority;}
        public void setAvailable(boolean available) {this.available = available;}
        public boolean isAvailable() {return this.available;}
        @Override
        public String toString() {return name;}
    }
    public static class SongSource
    {
        private Object id;
        private Source source;

        public SongSource(Object id, Source s)
        {
            this.id = id;
            this.source = s;
        }

        public Object getId() {return this.id;}
        public Source getSource() {return this.source;}
    }

    public static final int SOURCE_COUNT = 3;
    public static final Source SOURCE_LOCAL_LIB = new Source(R.drawable.ic_local, 0, "LOCAL");
    public static final Source SOURCE_SPOTIFY = new Source(R.drawable.ic_spotify, R.drawable.ic_spotify_logo, "SPOTIFY");
    public static final Source SOURCE_DEEZER = new Source(R.drawable.ic_deezer, R.drawable.ic_deezer, "DEEZER");

    public SongSource sources[] = new SongSource[SOURCE_COUNT];

    public void addSource(SongSource toAdd)
    {
        if(sources[0] != null && sources[0].getSource() == toAdd.getSource()
                || sources[1] != null && sources[1].getSource() == toAdd.getSource()
                || sources[2] != null && sources[2].getSource() == toAdd.getSource()) return;

        if(sources[0] == null) {sources[0] = toAdd;}
        else if(sources[0].getSource().getPriority() < toAdd.getSource().getPriority())
        {
            sources[2] = sources[1];
            sources[1] = sources[0];
            sources[0] = toAdd;
        }
        else if(sources[1] == null) sources[1] = toAdd;
        else if(sources[1].getSource().getPriority() < toAdd.getSource().getPriority())
        {
            sources[2] = sources[1];
            sources[1] = toAdd;
        }
        else if(sources[2] == null) sources[2] = toAdd;
        else System.err.println("Warning : incorrect call to addSource().");
    }

    public SongSource getSourceByPriority(int priority)
    {
        return sources[priority];
    }
    public SongSource getSpotify()
    {
        for(SongSource s : sources)
            if(s != null && s.getSource() == SOURCE_SPOTIFY) return s;
        return null;
    }
    public SongSource getDeezer()
    {
        for(SongSource s : sources)
            if(s != null && s.getSource() == SOURCE_DEEZER) return s;
        return null;
    }
}
