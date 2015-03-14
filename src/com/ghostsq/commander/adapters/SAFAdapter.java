package com.ghostsq.commander.adapters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Arrays;
import java.util.List;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.R;
import com.ghostsq.commander.utils.Utils;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.widget.AdapterView;

@SuppressLint("NewApi")
public class SAFAdapter extends CommanderAdapterBase implements Engines.IReciever {
    private final static String TAG = "SAFAdapter";

    static class SAFItem extends CommanderAdapter.Item  implements FSEngines.IFileItem {
        @Override
        public File f() {
            Uri u = (Uri)origin;
            String path = SAFAdapter.getPath( u );
            return new File( path );
        }
    }
    
    private   Uri    uri;
    protected SAFItem[] items;
    
    ThumbnailsThread tht = null;
    
    public SAFAdapter( Context ctx_ ) {
        super( ctx_ );
    }

    @Override
    public String getScheme() {
        return ContentResolver.SCHEME_CONTENT;
    }
    
    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case FS:
        case LOCAL:
        case REAL:
        case SF4:
        case SEND:
            return true;
        default: return super.hasFeature( feature );
        }
    }
    
    @Override
    public String toString() {
        return SAFAdapter.getPath( uri );
    }

    private static boolean isTreeUri( Uri uri ) {
        final String PATH_TREE = "tree";
        final List<String> paths = uri.getPathSegments();
        return paths.size() == 2 && PATH_TREE.equals(paths.get(0));
    }
    
    private static boolean isRootDoc( Uri uri ) {
        final List<String> paths = uri.getPathSegments();
        if( paths.size() < 4 ) return true;
        String last = paths.get(paths.size()-1); 
        return last.lastIndexOf( ':' ) == last.length()-1;
    }
    
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void setUri( Uri uri_ ) {
        if( this.uri == null && isTreeUri( uri_ ) ) {
            try {
                ctx.getContentResolver().takePersistableUriPermission( uri_,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION );
            } catch( Exception e ) {
                Log.e( TAG, uri_.toString() );
            }
            this.uri = DocumentsContract.buildDocumentUriUsingTree( uri_, 
                       DocumentsContract.getTreeDocumentId( uri_ ));        
        }
        else
            this.uri = uri_;
    }
    
    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
    	try {
    	    //if( worker != null ) worker.reqStop();
            if( tmp_uri != null ) {
                Log.d( TAG, "New URI: " + tmp_uri.toString() );
                setUri( tmp_uri );
            }
            if( uri == null ) {
                Log.e( TAG, "No URI" );
                return false;
            }
            Cursor c = null;
            try {
                try {
                    ContentResolver cr = ctx.getContentResolver();
                    Uri children_uri = DocumentsContract.buildChildDocumentsUriUsingTree( uri,
                                       DocumentsContract.getDocumentId( uri ) );
                    Log.d( TAG, "Children URI:" + children_uri );
                    final String[] projection = {
                         Document.COLUMN_DOCUMENT_ID,
                         Document.COLUMN_DISPLAY_NAME,
                         Document.COLUMN_LAST_MODIFIED,
                         Document.COLUMN_MIME_TYPE,
                         Document.COLUMN_SIZE,
                         Document.COLUMN_SUMMARY 
                    };
                  c = cr.query( children_uri, projection, null, null, null);
                } catch( SecurityException e ) {
                    commander.Navigate( Uri.parse( HomeAdapter.DEFAULT_LOC ), null, null );
                    return false;
                } catch( Exception e ) {
                    Log.e( TAG, "Failed children query for " + uri.toString(), e);
                }
                if( c != null && c.getCount() > 0 ) {
                  ArrayList<SAFItem>   tmp_list = new ArrayList<SAFItem>();
                  int ici = c.getColumnIndex( Document.COLUMN_DOCUMENT_ID );
                  int nci = c.getColumnIndex( Document.COLUMN_DISPLAY_NAME );
                  int sci = c.getColumnIndex( Document.COLUMN_SIZE );
                  int mci = c.getColumnIndex( Document.COLUMN_MIME_TYPE );
                  int dci = c.getColumnIndex( Document.COLUMN_LAST_MODIFIED );
                  c.moveToFirst();
                  do {
                      SAFItem item = new SAFItem();
                      String id = c.getString( ici );
                      item.origin = DocumentsContract.buildDocumentUriUsingTree( uri, id );
                      item.attr = c.getString( mci );
                      item.dir = Document.MIME_TYPE_DIR.equals( item.attr ); 
                      item.name = ( item.dir ? "/" : "" ) + c.getString( nci );
                      item.size = c.getLong( sci );
                      item.date = new Date( c.getLong( dci ) );
                      if( item.dir ) item.size = -1;
                      tmp_list.add( item );
                  } while( c.moveToNext() );
                  items = new SAFItem[tmp_list.size()];
                  tmp_list.toArray( items );
                  reSort( items );
               }
               else
                   items = new SAFItem[0];
               super.setCount( items.length );
            
            } catch(Exception e) {
                Log.e( TAG, "Failed cursor processing for " + uri.toString(), e );
            } finally {
                if( c != null ) c.close();
            }
            parentLink = isRootDoc( uri ) ? SLS : PLS;
            startThumbnailCreation();
            notifyDataSetChanged();
            notify( pass_back_on_done );
            return true;
        } catch( Exception e ) {
            Log.e( TAG, "readSource() exception", e );
        } catch( OutOfMemoryError err ) {
            Log.e( TAG, "Out Of Memory", err );
            notify( s( R.string.oom_err ), Commander.OPERATION_FAILED );
		}
		return false;
    }

    protected void startThumbnailCreation() {
        if( thumbnail_size_perc > 0 ) {
            //Log.i( TAG, "thumbnails " + thumbnail_size_perc );
            if( tht != null )
                tht.interrupt();
            
            Handler h = new Handler() {
                public void handleMessage( Message msg ) {
                    notifyDataSetChanged();
                } };            
            tht = new ThumbnailsThread( this, h, Utils.mbAddSl( getPath( uri ) ), items );
            tht.start();
        }
    }
    
    @Override
    public void populateContextMenu( ContextMenu menu, AdapterView.AdapterContextMenuInfo acmi, int num ) {
        try {
            if( acmi.position != 0 ) {
                Item item = (Item)getItem( acmi.position );
                if( !item.dir && ".zip".equals( Utils.getFileExt( item.name ) ) ) {
                    menu.add( 0, R.id.open, 0, R.string.open );
                    menu.add( 0, R.id.extract, 0, R.string.extract_zip );
                }
                if( item.dir && num == 1 && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO )
                    menu.add( 0, R.id.rescan_dir, 0, R.string.rescan );
            }
            super.populateContextMenu( menu, acmi, num );
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
    }

    @Override
    public void doIt( int command_id, SparseBooleanArray cis ) {
    }
    
    @Override
    public void openItem( int position ) {
        if( position == 0 ) {
            Uri uri_to_go = null;
            if( parentLink == SLS ) 
                uri_to_go = Uri.parse( HomeAdapter.DEFAULT_LOC );
            else {
                //0000-0000:folder%2Fsubfolder
                final List<String> paths = uri.getPathSegments();
                final int n = paths.size();
                if( n < 4 ) {
                    uri_to_go = Uri.parse( HomeAdapter.DEFAULT_LOC );
                }
                else {
                    StringBuffer sb = new StringBuffer();
                    for( int i = 0; i < n-1; i++ ) {
                        sb.append( "/" );
                        sb.append( paths.get( i ) );
                    }
                    if( n == 4 ) {
                        String last = paths.get( n-1 ); 
                        int col_pos = last.lastIndexOf( ':' );
                        if( col_pos <= 0 || col_pos == last.length()-1 )
                            uri_to_go = Uri.parse( HomeAdapter.DEFAULT_LOC );
                        else {
                            sb.append( "/" );
                            sb.append( last.substring( 0, col_pos+1 ) );
                            String subpath = last.substring( col_pos+1 );
                            subpath = Uri.decode( subpath );
                            int sl_pos = subpath.lastIndexOf( SLC );
                            if( sl_pos > 0 ) {
                                subpath = subpath.substring( 0, sl_pos );
                                sb.append( Uri.encode( subpath ) );
                            }
                        }
                    }
                    if( uri_to_go == null )
                        uri_to_go = uri.buildUpon().encodedPath( sb.toString() ).build();
                }
            }
            commander.Navigate( uri_to_go, null, null );
        }
        else {
            Item item = items[position - 1];
            if( item.dir )
                commander.Navigate( (Uri)item.origin, null, null );
            else {
                Intent i = new Intent( Intent.ACTION_VIEW );
                i.setDataAndType( Uri.parse( "file:///" + Utils.escapePath( getItemName( position, true ) ) ), item.attr );
                commander.issue( i, 0 );
            }
        }
    }

    @Override
    public Uri getItemUri( int position ) {
        try {
            return (Uri)items[position - 1].origin;
            /*
            String item_name = getItemName( position, true );
            return Uri.parse( Utils.escapePath( item_name ) );
            */
        } catch( Exception e ) {
            Log.e( TAG, "No item in the position " + position );
        }
        return null;
    }
    @Override
    public String getItemName( int position, boolean full ) {
        if( position == 0 ) return parentLink; 
        if( position < 0 || items == null || position > items.length )
            return null;
        if( full ) {
            Uri item_uri = (Uri)items[position - 1].origin;
            return getPath( item_uri );
        } else
            return items[position - 1].name;
    }

    private static String getPath( Uri u ) {
        final List<String> paths = u.getPathSegments();
        if( paths.size() < 4 ) return null;
        String path_part = paths.get( 3 );
        int col_pos = path_part.lastIndexOf( ':' );
        return "/sdcard/" + path_part.substring( col_pos+1 ); // FIXME: apparently, not a very correct way
    }
    
    @Override
	public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            SAFItem[] list = bitsToItems( cis );
            if( list != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new FSEngines.CalcSizesEngine( this, list ) );
            }
		}
        catch(Exception e) {
		}
	}
	
	@Override
    public boolean renameItem( int position, String newName, boolean copy ) {
            return false;
    }
	
    @Override
    public Item getItem( Uri u ) {
        try {
            File f = new File( u.getPath() );
            if( f.exists() ) {
                Item item = new Item( f.getName() );
                item.size = f.length();
                item.date = new Date( f.lastModified() );
                item.dir = f.isDirectory();
                return item;
            }
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }
	
    @Override
    public InputStream getContent( Uri u, long skip ) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            InputStream is = cr.openInputStream( u );
            if( is == null ) return null;
            if( skip > 0 )
                is.skip( skip );
            return is;
        } catch( Throwable e ) {
            e.printStackTrace();
        }
        return null;
    }
    
    @Override
    public OutputStream saveContent( Uri u ) {
        if( u != null ) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                return cr.openOutputStream( u );
            } catch( FileNotFoundException e ) {
                Log.e( TAG, u.getPath(), e );
            }
        }
        return null;
    }
    
	@Override
	public boolean createFile( String fileURI ) {
		try {
			File f = new File( fileURI );
			boolean ok = f.createNewFile();
			notify( null, ok ? Commander.OPERATION_COMPLETED_REFRESH_REQUIRED : Commander.OPERATION_FAILED );
			return ok;     
		} catch( Exception e ) {
		    commander.showError( ctx.getString( R.string.cant_create, fileURI, e.getMessage() ) );
		}
		return false;
	}
    @Override
    public void createFolder( String new_name ) {
        try {
            Uri new_uri = DocumentsContract.createDocument( ctx.getContentResolver(), uri, Document.MIME_TYPE_DIR, new_name );
            if( new_uri != null ) {
                notifyRefr( new_name );
                return;
            }
        } catch( Exception e ) {
            Log.e( TAG, "createFolder", e );
        }
        notify( ctx.getString( R.string.cant_md, new_name ), Commander.OPERATION_FAILED );
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
    	try {
        	Item[] list = bitsToItems( cis );
        	if( list != null ) {
        		notify( Commander.OPERATION_STARTED );
        		commander.startEngine( new DeleteEngine( list ) );
        	}
		} catch( Exception e ) {
		    notify( e.getMessage(), Commander.OPERATION_FAILED );
		}
        return false;
    }

    class DeleteEngine extends Engine {
        private Item[] mList;
        private Uri dirUri;
        private ContentResolver cr;

        DeleteEngine( Item[] list ) {
            setName( ".DeleteEngine" );
            mList = list;
            dirUri = SAFAdapter.this.getUri();
        }
        @Override
        public void run() {
            try {
                Init( null );
                cr = ctx.getContentResolver();
                int cnt = deleteFiles( dirUri, mList );
                sendResult( Utils.getOpReport( ctx, cnt, R.string.deleted ) );
            }
            catch( Exception e ) {
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
            }
        }
        
        private final int deleteFiles( Uri dir_uri, Item[] l ) throws Exception {
            if( l == null ) return 0;
            int cnt = 0;
            int num = l.length;
            double conv = 100./num;
            for( int i = 0; i < num; i++ ) {
                sleep( 1 );
                if( isStopReq() )
                    throw new Exception( s( R.string.canceled ) );
                Item item = l[i];
                sendProgress( ctx.getString( R.string.deleting, item.name ), (int)(cnt * conv) );
                DocumentsContract.deleteDocument( cr, (Uri)item.origin );
                cnt++;
            }
            return cnt;
        }
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        boolean ok = to.receiveItems( bitsToNames( cis ), move ? MODE_MOVE : MODE_COPY );
        if( !ok ) notify( Commander.OPERATION_FAILED );
        return ok;
    }

    @Override
    public boolean receiveItems( String[] uris, int move_mode ) {
        try {
            if( uris == null || uris.length == 0 )
                return false;
            File[] list = Utils.getListOfFiles( uris );
            if( list != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new CopyEngine( list, move_mode ) );
                return true;
            }
        } catch( Exception e ) {
            e.printStackTrace();
        }
        return false;
    }

    class CopyEngine extends Engine {
        private Uri     mDest;
        private ContentResolver cr;
        private int     counter = 0, delerr_counter = 0, depth = 0;
        private long    totalBytes = 0;
        private double  conv;
        private File[]  fList = null;
        private boolean move, del_src_dir;
        private byte[]  buf;
        private static final int BUFSZ = 524288;
        private PowerManager.WakeLock wakeLock;

        CopyEngine( File[] list, int move_mode ) {
            super( null );
            setName( ".CopyEngine" );
            fList = list;
            mDest = SAFAdapter.this.getUri();
            cr = SAFAdapter.this.ctx.getContentResolver();
            move = ( move_mode & MODE_MOVE ) != 0;
            del_src_dir = ( move_mode & MODE_DEL_SRC_DIR ) != 0;
            buf = new byte[BUFSZ];
            PowerManager pm = (PowerManager)ctx.getSystemService( Context.POWER_SERVICE );
            wakeLock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, TAG );
        }
        @Override
        public void run() {
            sendProgress( ctx.getString( R.string.preparing ), 0, 0 );
            try {
                int l = fList.length;
                Item[] x_list = new Item[l];
                wakeLock.acquire();
//                long sum = getSizes( x_list );
//                conv = 100 / (double)sum;
                int num = copyFiles( fList, mDest );

                if( del_src_dir ) {
                    File src_dir = fList[0].getParentFile();
                    if( src_dir != null )
                        src_dir.delete();
                }
                wakeLock.release();
                // XXX: assume (move && !del_src_dir)==true when copy from app: to the FS
                if( delerr_counter == counter ) move = false;  // report as copy
                String report = Utils.getOpReport( ctx, num, move && !del_src_dir ? R.string.moved : R.string.copied );
                sendResult( report );
            } catch( Exception e ) {
                sendProgress( e.getMessage(), Commander.OPERATION_FAILED_REFRESH_REQUIRED );
                return;
            }
        }
        
        private final String getMime( Uri u ) {
            Cursor c = null;
            try {
                final String[] projection = { Document.COLUMN_MIME_TYPE };
                
                c = cr.query( u, projection, null, null, null );
                if( c.getCount() > 0 ) {
                    c.moveToFirst();
                    return c.getString( 0 );
                }
            } catch( Exception e ) {
            } finally {
                if( c != null ) c.close();
            }
            return null;
        }
        
        private final int copyFiles( File[] list, Uri dest ) throws InterruptedException {
            File file = null;
            for( int i = 0; i < list.length; i++ ) {
                InputStream  is = null;
                OutputStream os = null;
                file = list[i];
                if( file == null ) {
                    error( ctx.getString( R.string.unkn_err ) );
                    break;
                }
                Uri dest_uri = null;
                try {
                    if( isStopReq() ) {
                        error( ctx.getString( R.string.canceled ) );
                        break;
                    }
                    String fn = file.getName();
                    String to_append = "%2f" + Utils.escapePath( fn );
                    dest_uri = dest.buildUpon().encodedPath( dest.getEncodedPath() + to_append ).build();
                    String mime = getMime( dest_uri );
                    if( file.isDirectory() ) {
                        if( depth++ > 40 ) {
                            error( ctx.getString( R.string.too_deep_hierarchy ) );
                            break;
                        }
                        if( mime != null ) {
                          if( !Document.MIME_TYPE_DIR.equals( mime ) ) {
                            error( ctx.getString( R.string.cant_md ) );
                            break;
                          }
                        } else {
                            DocumentsContract.createDocument( cr, dest, Document.MIME_TYPE_DIR, fn );                            
                        }
                        copyFiles( file.listFiles(), dest_uri );
                        if( errMsg != null )
                            break;
                        depth--;
                        counter++;
                    }
                    else {
                        if( mime != null ) {
                            int res = askOnFileExist( ctx.getString( R.string.file_exist, fn ), commander );
                            if( res == Commander.SKIP )  continue;
                            if( res == Commander.ABORT ) break;
                            if( res == Commander.REPLACE ) Log.v( TAG, "Overwritting file " + fn );
                        } else
                            mime = Utils.getMimeByExt( Utils.getFileExt( fn ) );
                        dest_uri = DocumentsContract.createDocument( cr, dest, mime, fn );
                        is = new FileInputStream( file );
                        os = cr.openOutputStream( dest_uri );
                        long copied = 0, size  = file.length();
                        
                        long start_time = 0;
                        int  speed = 0;
                        int  so_far = (int)(totalBytes * conv);
                        
                        String sz_s = Utils.getHumanSize( size );
                        int fnl = fn.length();
                        String rep_s = ctx.getString( R.string.copying, 
                               fnl > CUT_LEN ? "\u2026" + fn.substring( fnl - CUT_LEN ) : fn );
                        int  n  = 0; 
                        long nn = 0;
                        
                        while( true ) {
                            if( nn == 0 ) {
                                start_time = System.currentTimeMillis();
                                sendProgress( rep_s + sizeOfsize( copied, sz_s ), so_far, (int)(totalBytes * conv), speed );
                            }
                            n = is.read( buf );
                            if( n < 0 ) {
                                long time_delta = System.currentTimeMillis() - start_time;
                                if( time_delta > 0 ) {
                                    speed = (int)(MILLI * nn / time_delta );
                                    sendProgress( rep_s + sizeOfsize( copied, sz_s ), so_far, (int)(totalBytes * conv), speed );
                                }
                                break;
                            }
                            os.write( buf, 0, n );
                            nn += n;
                            copied += n;
                            totalBytes += n;
                            if( isStopReq() ) {
                                Log.d( TAG, "Interrupted!" );
                                error( ctx.getString( R.string.canceled ) );
                                return counter;
                            }
                            long time_delta = System.currentTimeMillis() - start_time;
                            if( time_delta > DELAY ) {
                                speed = (int)(MILLI * nn / time_delta);
                                //Log.v( TAG, "bytes: " + nn + " time: " + time_delta + " speed: " + speed );
                                nn = 0;
                            }
                        }
                        is.close();
                        os.close();
                        is = null;
                        os = null;
                        if( i >= list.length-1 )
                            sendProgress( ctx.getString( R.string.copied_f, fn ) + sizeOfsize( copied, sz_s ), (int)(totalBytes * conv) );
                        counter++;
                    }
                    if( move ) {
                        if( !file.delete() ) {
                            sendProgress( ctx.getString( R.string.cant_del, fn ), -1 );
                            delerr_counter++;
                        }
                    }
                }
                catch( Exception e ) {
                    Log.e( TAG, "", e );
                    error( ctx.getString( R.string.rtexcept, file.getAbsolutePath(), e.getMessage() ) );
                }
                finally {
                    try {
                        if( is != null )
                            is.close();
                        if( os != null )
                            os.close();
                    }
                    catch( IOException e ) {
                        error( ctx.getString( R.string.acc_err, file.getAbsolutePath(), e.getMessage() ) );
                    }
                }
            }
            return counter;
        }
    }
    
    
    @Override
	public void prepareToDestroy() {
        super.prepareToDestroy();
        if( tht != null )
            tht.interrupt();
	}


    @Override
    protected int getPredictedAttributesLength() {
        return 24;   // "application/octet-stream"
    }
    
    /*
     *  ListAdapter implementation
     */

    @Override
    public int getCount() {
        if( items == null )
            return 1;
        return items.length + 1;
    }

    @Override
    public Object getItem( int position ) {
        Item item = null;
        if( position == 0 ) {
            item = new Item();
            item.name = parentLink;
            item.dir = true;
        }
        else {
            if( items != null && position <= items.length ) {
                return items[position - 1];
            }
            else {
                item = new Item();
                item.name = "???";
            }
        }
        return item;
    }
    public final SAFItem[] bitsToItems( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) && cis.keyAt( i ) > 0)
                    counter++;
            SAFItem[] res = new SAFItem[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) ) {
                    int k = cis.keyAt( i );
                    if( k > 0 )
                        res[j++] = items[ k - 1 ];
                }
            return res;
        } catch( Exception e ) {
            Log.e( TAG, "bitsToFiles()", e );
        }
        return null;
    }

    @Override
    protected void reSort() {
        if( items == null ) return;
        synchronized( items ) {
            reSort( items );
        }
    }
    public void reSort( Item[] items_ ) {
        if( items_ == null ) return;
        ItemComparator comp = new ItemComparator( mode & MODE_SORTING, (mode & MODE_CASE) != 0, ascending );
        Arrays.sort( items_, comp );
    }
    @Override
    public IReciever getReceiver() {
        return this;
    }
}