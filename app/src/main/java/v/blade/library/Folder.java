package v.blade.library;

import android.os.Environment;
import v.blade.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class Folder extends LibraryObject
{
    public static final Folder root = new Folder("/", new ArrayList<>());
    private ArrayList<LibraryObject> objects;

    public Folder(String name, ArrayList<LibraryObject> objs)
    {
        this.name = name;
        this.objects = objs;
    }

    public ArrayList<LibraryObject> getContent() {return objects;}
    public ArrayList<Song> getSongContent()
    {
        ArrayList<Song> tr = new ArrayList<>();
        for(LibraryObject e : objects)
            if(e instanceof Song) tr.add((Song) e);
        return tr;
    }
    public int getSongPosition(Song song)
    {
        int tr = 0;
        for(LibraryObject e : objects)
        {
            if(e instanceof Song)
            {
                if(e == song) return tr;
                tr++;
            }
        }
        return 0;
    }
    public boolean contains(String f)
    {
        for(LibraryObject o : objects)
            if(o.getName().equals(f)) return true;
        return false;
    }
    public Folder getFolder(String f)
    {
        for(LibraryObject o : objects)
            if(o.getName().equals(f) && o instanceof Folder) return ((Folder) o);
        return null;
    }
    public Folder createFolder(String f)
    {
        Folder tr = new Folder(f, new ArrayList<>());
        objects.add(tr);
        return tr;
    }

    public static void addToFolder(String path, LibraryObject object)
    {
        path = path.replaceAll(Environment.getExternalStorageDirectory().getAbsolutePath(), "/" + (Environment.isExternalStorageRemovable() ? LibraryService.appContext.getResources().getString(R.string.external_storage) : LibraryService.appContext.getResources().getString(R.string.internal_storage)));
        String[] sp = path.split("/");
        int len = sp.length;
        Folder current = root;
        for(int i = 1; i<len-1;i++)
        {
            String s = sp[i];
            Folder got = current.getFolder(s);
            if(got != null) current = got;
            else current = current.createFolder(s);
        }
        current.objects.add(object);
    }

    public void sortFolder()
    {
        Collections.sort(objects, new Comparator<LibraryObject>()
        {
            public int compare(LibraryObject a, LibraryObject b)
            {
                if(a instanceof Folder && !(b instanceof Folder)) return 0;
                else if(!(a instanceof Folder) && b instanceof Folder) return 1;
                if(a.getName() != null && b.getName() != null) return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
                else return 0;
            }
        });
    }
    public static void sortFolders(Folder f)
    {
        f.sortFolder();
        for(LibraryObject l : f.getContent())
        {
            if(l instanceof Folder) sortFolders((Folder) l);
        }
    }
}
