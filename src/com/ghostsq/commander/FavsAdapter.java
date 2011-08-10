package com.ghostsq.commander;

import java.util.ArrayList;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.CommanderAdapter;
import com.ghostsq.commander.CommanderAdapterBase;

import android.net.Uri;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

public class FavsAdapter extends CommanderAdapterBase {
    private final static String TAG = "FavsAdapter";
    private final static int SCUT_CMD = 26945;
    private ArrayList<Favorite> favs;
    
    public FavsAdapter( Commander c ) {
        super( c, DETAILED_MODE | NARROW_MODE | SHOW_ATTR | ATTR_ONLY );
        numItems = 0;
        favs = null;
        numItems = 1;
    }

    public void setFavorites( ArrayList<Favorite> favs_ ) {
        favs = favs_;
        numItems = favs.size() + 1; 
    }
    
    @Override
    public int setMode( int mask, int val ) {
        if( ( mask & ( MODE_WIDTH | MODE_DETAILS | MODE_ATTR ) ) == 0 )
            super.setMode( mask, val );
        return mode;
    }    
    
    @Override
    public String getType() {
        return "favs";
    }
    
    @Override
    public String toString() {
        return "favs:";
    }
    /*
     * CommanderAdapter implementation
     */
    @Override
    public Uri getUri() {
        return Uri.parse( toString() );
    }

    @Override
    public boolean readSource( Uri tmp_uri, String pbod ) {
        commander.notifyMe( new Commander.Notify( null, Commander.OPERATION_COMPLETED, pbod ) );
        return true;
    }

    @Override
    public boolean isButtonActive( int brId ) {
        if( brId == R.id.F1 ||
            brId == R.id.F2 ||
            brId == R.id.F4 ||
            brId == R.id.F8 ||
            brId == R.id.F9 ||
            brId == R.id.F10 ) return true;
        return false;
    }

    @Override
    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num ) {
        if( num <= 1 ) {
            menu.add( 0, Commander.OPEN, 0, s( R.string.go_button ) );
            menu.add( 0, R.id.F2,        0, s( R.string.rename_title ) );
            menu.add( 0, R.id.F4,        0, s( R.string.edit_title ) );
            menu.add( 0, R.id.F8,        0, s( R.string.delete_title ) );
            //menu.add( 0, SCUT_CMD,       0, s( R.string.shortcut ) );     // TODO
        }
    }    
    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        notErr();
    }
    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        return notErr();
    }
        
    @Override
    public boolean createFile( String fileURI ) {
        return notErr();
    }

    @Override
    public void createFolder( String new_name ) {
        notErr();
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        for( int i = 0; i < cis.size(); i++ )
            if( cis.valueAt( i ) ) {
                int k = cis.keyAt( i );
                if( k > 0 ) {
                    favs.remove( k - 1 );
                    numItems--;
                    notifyDataSetChanged();
                    commander.notifyMe( new Commander.Notify( null, Commander.OPERATION_COMPLETED, null ) );
                    return true;
                }
            }
        return false;
    }
    
    @Override
    public void openItem( int position ) {
        if( position == 0 ) {
            commander.Navigate( Uri.parse( "home:" ), null );
            return;
        }
        if( favs == null || position < 0 || position > numItems )
            return;
        commander.Navigate( favs.get( position - 1 ).getUriAuth(), null );
    }

    @Override
    public boolean receiveItems( String[] full_names, int move_mode ) {
        return notErr();
    }
    
    @Override
    public boolean renameItem( int p, String newName, boolean c  ) {
        if( favs != null && p > 0 && p <= favs.size() ) {
            favs.get( p-1 ).setComment( newName );
            notifyDataSetChanged();
            return true;
        }
        return false;
    }

    public void editItem( int p ) {
        if( favs != null && p > 0 && p <= favs.size() ) {
            new FavDialog( commander.getContext(), favs.get( p-1 ), this );
        }
    }    
    
    @Override
    public String getItemName( int p, boolean full ) {
        if( favs != null && p > 0 && p <= favs.size() ) {
            String comm = favs.get( p-1 ).getComment();
            return comm == null ? "" : comm; // .getUriString( true );
        }
        return null;
    }
    
    /*
     * BaseAdapter implementation
     */

    @Override
    public Object getItem( int position ) {
        Item item = new Item();
        if( position == 0 ) {
            item = new Item();
            item.name = parentLink;
            item.dir = true;
        }
        else {
            if( favs != null && position > 0 && position <= favs.size() ) {
                Favorite f = favs.get( position - 1 );
                if( f != null ) {
                    item.dir = false;
                    item.name = f.getUriString( true );
                    item.size = -1;
                    item.sel = false;
                    item.date = null;
                    item.attr = f.getComment();
                    
                    Uri uri = f.getUri();
                    if( uri != null ) {
                        String sch = uri.getScheme();
                        if( sch == null || sch.length() == 0 )
                            item.icon_id = R.drawable.folder;
                        else {
                            int scheme_h = GetAdapterTypeHash( sch );
                            if(  zip_schema_h == scheme_h )  item.icon_id = R.drawable.zip;     else   
                            if(  ftp_schema_h == scheme_h )  item.icon_id = R.drawable.server;  else   
                            if( root_schema_h == scheme_h )  item.icon_id = R.drawable.root;    else  
                            if(  mnt_schema_h == scheme_h )  item.icon_id = R.drawable.mount;   else  
                            if(  smb_schema_h == scheme_h )  item.icon_id = R.drawable.smb;     else
                                item.icon_id = R.drawable.folder;
                        }
                    }
                }
            }
        }
        return item;
    }

    @Override
    public View getView( int position, View convertView, ViewGroup parent ) {
        Item item = (Item)getItem( position );
        if( item == null ) return null;
        return getView( convertView, parent, item );
    }

}
