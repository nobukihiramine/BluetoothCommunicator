package com.hiramine.bluetoothcommunicator;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements View.OnClickListener
{
	static public class BluetoothService
	{
		// 定数（Bluetooth UUID）
		private static final UUID UUID_SPP = UUID.fromString( "00001101-0000-1000-8000-00805f9b34fb" );

		// 定数
		public static final int MESSAGE_STATECHANGE    = 1;
		public static final int MESSAGE_READ           = 2;
		public static final int MESSAGE_WRITTEN        = 3;
		public static final int STATE_NONE             = 0;
		public static final int STATE_CONNECT_START    = 1;
		public static final int STATE_CONNECT_FAILED   = 2;
		public static final int STATE_CONNECTED        = 3;
		public static final int STATE_CONNECTION_LOST  = 4;
		public static final int STATE_DISCONNECT_START = 5;
		public static final int STATE_DISCONNECTED     = 6;

		// メンバー変数
		private int              mState;
		private ConnectionThread mConnectionThread;
		private Handler          mHandler;

		// 接続時処理用のスレッド
		private class ConnectionThread extends Thread
		{
			private BluetoothSocket mBluetoothSocket;
			private InputStream     mInput;
			private OutputStream    mOutput;

			// コンストラクタ
			public ConnectionThread( BluetoothDevice bluetoothdevice )
			{
				try
				{
					mBluetoothSocket = bluetoothdevice.createRfcommSocketToServiceRecord( UUID_SPP );
					mInput = mBluetoothSocket.getInputStream();
					mOutput = mBluetoothSocket.getOutputStream();
				}
				catch( IOException e )
				{
					Log.e( "BluetoothService", "failed : bluetoothdevice.createRfcommSocketToServiceRecord( UUID_SPP )", e );
				}
			}

			// 処理
			public void run()
			{
				while( STATE_DISCONNECTED != mState )
				{
					switch( mState )
					{
						case STATE_NONE:
							break;
						case STATE_CONNECT_START:    // 接続開始
							try
							{
								// BluetoothSocketオブジェクトを用いて、Bluetoothデバイスに接続を試みる。
								mBluetoothSocket.connect();
							}
							catch( IOException e )
							{    // 接続失敗
								Log.d( "BluetoothService", "Failed : mBluetoothSocket.connect()" );
								setState( STATE_CONNECT_FAILED );
								cancel();    // スレッド終了。
								return;
							}
							// 接続成功
							setState( STATE_CONNECTED );
							break;
						case STATE_CONNECT_FAILED:        // 接続失敗
							// 接続失敗時の処理の実体は、cancel()。
							break;
						case STATE_CONNECTED:        // 接続済み（Bluetoothデバイスから送信されるデータ受信）
							byte[] buf = new byte[1024];
							int bytes;
							try
							{
								bytes = mInput.read( buf );
								mHandler.obtainMessage( MESSAGE_READ, bytes, -1, buf ).sendToTarget();
							}
							catch( IOException e )
							{
								setState( STATE_CONNECTION_LOST );
								cancel();    // スレッド終了。
								break;
							}
							break;
						case STATE_CONNECTION_LOST:    // 接続ロスト
							// 接続ロスト時の処理の実体は、cancel()。
							break;
						case STATE_DISCONNECT_START:    // 切断開始
							// 切断開始時の処理の実体は、cancel()。
							break;
						case STATE_DISCONNECTED:    // 切断完了
							// whileの条件式により、STATE_DISCONNECTEDの場合は、whileを抜けるので、このケース分岐は無意味。
							break;
					}
				}
				synchronized( BluetoothService.this )
				{    // 親クラスが保持する自スレッドオブジェクトの解放（自分自身の解放）
					mConnectionThread = null;
				}
			}

			// キャンセル（接続を終了する。ステータスをSTATE_DISCONNECTEDにすることによってスレッドも終了する）
			public void cancel()
			{
				try
				{
					mBluetoothSocket.close();
				}
				catch( IOException e )
				{
					Log.e( "BluetoothService", "Failed : mBluetoothSocket.close()", e );
				}
				setState( STATE_DISCONNECTED );
			}

			// バイト列送信
			public void write( byte[] buf )
			{
				try
				{
					synchronized( BluetoothService.this )
					{
						mOutput.write( buf );
					}
					mHandler.obtainMessage( MESSAGE_WRITTEN ).sendToTarget();
				}
				catch( IOException e )
				{
					Log.e( "BluetoothService", "Failed : mBluetoothSocket.close()", e );
				}
			}
		}

		// コンストラクタ
		public BluetoothService( Context context, Handler handler, BluetoothDevice device )
		{
			mHandler = handler;
			mState = STATE_NONE;

			// 接続時処理用スレッドの作成と開始
			mConnectionThread = new ConnectionThread( device );
			mConnectionThread.start();
		}

		// ステータス設定
		private synchronized void setState( int state )
		{
			mState = state;
			mHandler.obtainMessage( MESSAGE_STATECHANGE, state, -1 ).sendToTarget();
		}

		// 接続開始時の処理
		public synchronized void connect()
		{
			if( STATE_NONE != mState )
			{    // １つのBluetoothServiceオブジェクトに対して、connect()は１回だけ呼べる。
				// ２回目以降の呼び出しは、処理しない。
				return;
			}

			// ステータス設定
			setState( STATE_CONNECT_START );
		}

		// 接続切断時の処理
		public synchronized void disconnect()
		{
			if( STATE_CONNECTED != mState )
			{    // 接続中以外は、処理しない。
				return;
			}

			// ステータス設定
			setState( STATE_DISCONNECT_START );

			mConnectionThread.cancel();
		}

		// バイト列送信（非同期）
		public void write( byte[] out )
		{
			ConnectionThread connectionThread;
			synchronized( this )
			{
				if( STATE_CONNECTED != mState )
				{
					return;
				}
				connectionThread = mConnectionThread;
			}
			// 非同期送信
			// （送受信で同期（送信と受信を排他処理（≒同期処理））させる実装も可能だが、
			// 　そうすると、mInput.read( buf ) が完了するまで、mOutput.write( buf ) が実施されなくなる。
			// 　mInput.read( buf ) は文字列を受信すると完了するので、文字列を受信しなければいつまでたっても完了しない。
			// 　文字列が頻繁に送信されてくる場合はよいが、文字列がぜんぜん送信されてこない場合は、
			// 　こちらからの送信がいつまでたっても実施されないことになる。なので、受信と送信は非同期。）
			connectionThread.write( out );
		}
	}

	// 定数
	private static final int REQUEST_ENABLEBLUETOOTH = 1; // Bluetooth機能の有効化要求時の識別コード
	private static final int REQUEST_CONNECTDEVICE   = 2; // デバイス接続要求時の識別コード
	private static final int READBUFFERSIZE          = 1024;    // 受信バッファーのサイズ

	// メンバー変数
	private BluetoothAdapter mBluetoothAdapter;    // BluetoothAdapter : Bluetooth処理で必要
	private String mDeviceAddress = "";    // デバイスアドレス
	private BluetoothService mBluetoothService;    // BluetoothService : Bluetoothデバイスとの通信処理を担う
	private byte[] mReadBuffer        = new byte[READBUFFERSIZE];
	private int    mReadBufferCounter = 0;

	// GUIアイテム
	private Button mButton_Connect;    // 接続ボタン
	private Button mButton_Disconnect;    // 切断ボタン
	private Button mButton_WriteHello;        // 「Hello」送信ボタン
	private Button mButton_WriteWorld;        // 「World」送信ボタン

	// Bluetoothサービスから情報を取得するハンドラ
	private final Handler mHandler = new Handler()
	{
		// ハンドルメッセージ
		// UIスレッドの処理なので、UI処理について、runOnUiThread対応は、不要。
		@Override
		public void handleMessage( Message msg )
		{
			switch( msg.what )
			{
				case BluetoothService.MESSAGE_STATECHANGE:
					switch( msg.arg1 )
					{
						case BluetoothService.STATE_NONE:            // 未接続
							break;
						case BluetoothService.STATE_CONNECT_START:        // 接続開始
							break;
						case BluetoothService.STATE_CONNECT_FAILED:            // 接続失敗
							Toast.makeText( MainActivity.this, "Failed to connect to the device.", Toast.LENGTH_SHORT ).show();
							break;
						case BluetoothService.STATE_CONNECTED:    // 接続完了
							// GUIアイテムの有効無効の設定
							// 切断ボタン、文字列送信ボタンを有効にする
							mButton_Disconnect.setEnabled( true );
							mButton_WriteHello.setEnabled( true );
							mButton_WriteWorld.setEnabled( true );
							break;
						case BluetoothService.STATE_CONNECTION_LOST:            // 接続ロスト
							//Toast.makeText( MainActivity.this, "Lost connection to the device.", Toast.LENGTH_SHORT ).show();
							break;
						case BluetoothService.STATE_DISCONNECT_START:
							// GUIアイテムの有効無効の設定
							// 切断ボタン、文字列送信ボタンを無効にする
							mButton_Disconnect.setEnabled( false );
							mButton_WriteHello.setEnabled( false );
							mButton_WriteWorld.setEnabled( false );
							break;
						case BluetoothService.STATE_DISCONNECTED:            // 切断完了
							// GUIアイテムの有効無効の設定
							// 接続ボタンを有効にする
							mButton_Connect.setEnabled( true );
							mBluetoothService = null;    // BluetoothServiceオブジェクトの解放
							break;
					}
					break;
				case BluetoothService.MESSAGE_READ:
					byte[] abyteRead = (byte[])msg.obj;
					int iCountBuf = msg.arg1;
					for( int i = 0; i < iCountBuf; i++ )
					{
						byte c = abyteRead[i];
						if( '\r' == c )
						{    // 終端
							mReadBuffer[mReadBufferCounter] = '\0';
							// GUIアイテムへの反映
							( (TextView)findViewById( R.id.textview_read ) ).setText( new String( mReadBuffer, 0, mReadBufferCounter ) );
							mReadBufferCounter = 0;
						}
						else if( '\n' == c )
						{
							;    // 何もしない
						}
						else
						{    // 途中
							if( ( READBUFFERSIZE - 1 ) > mReadBufferCounter )
							{    // mReadBuffer[READBUFFERSIZE - 2] までOK。
								// mReadBuffer[READBUFFERSIZE - 1] は、バッファー境界内だが、「\0」を入れられなくなるのでNG。
								mReadBuffer[mReadBufferCounter] = c;
								mReadBufferCounter++;
							}
							else
							{    // バッファーあふれ。初期化
								mReadBufferCounter = 0;
							}
						}
					}
					break;
				case BluetoothService.MESSAGE_WRITTEN:
					// GUIアイテムの有効無効の設定
					// 文字列送信ボタンを有効にする（連打対策で無効になっているボタンを復帰させる）
					mButton_WriteHello.setEnabled( true );
					mButton_WriteWorld.setEnabled( true );
					break;
			}
		}
	};

	@Override
	protected void onCreate( Bundle savedInstanceState )
	{
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_main );

		// GUIアイテム
		mButton_Connect = (Button)findViewById( R.id.button_connect );
		mButton_Connect.setOnClickListener( this );
		mButton_Disconnect = (Button)findViewById( R.id.button_disconnect );
		mButton_Disconnect.setOnClickListener( this );
		mButton_WriteHello = (Button)findViewById( R.id.button_writehello );
		mButton_WriteHello.setOnClickListener( this );
		mButton_WriteWorld = (Button)findViewById( R.id.button_writeworld );
		mButton_WriteWorld.setOnClickListener( this );

		// Bluetoothアダプタの取得
		BluetoothManager bluetoothManager = (BluetoothManager)getSystemService( Context.BLUETOOTH_SERVICE );
		mBluetoothAdapter = bluetoothManager.getAdapter();
		if( null == mBluetoothAdapter )
		{    // Android端末がBluetoothをサポートしていない
			Toast.makeText( this, R.string.bluetooth_is_not_supported, Toast.LENGTH_SHORT ).show();
			finish();    // アプリ終了宣言
			return;
		}
	}

	// 初回表示時、および、ポーズからの復帰時
	@Override
	protected void onResume()
	{
		super.onResume();

		// Android端末のBluetooth機能の有効化要求
		requestBluetoothFeature();

		// GUIアイテムの有効無効の設定
		mButton_Connect.setEnabled( false );
		mButton_Disconnect.setEnabled( false );
		mButton_WriteHello.setEnabled( false );
		mButton_WriteWorld.setEnabled( false );

		// デバイスアドレスが空でなければ、接続ボタンを有効にする。
		if( !mDeviceAddress.equals( "" ) )
		{
			mButton_Connect.setEnabled( true );
		}

		// 接続ボタンを押す
		mButton_Connect.callOnClick();
	}

	// 別のアクティビティ（か別のアプリ）に移行したことで、バックグラウンドに追いやられた時
	@Override
	protected void onPause()
	{
		super.onPause();

		// 切断
		disconnect();
	}

	// アクティビティの終了直前
	@Override
	protected void onDestroy()
	{
		super.onDestroy();

		if( null != mBluetoothService )
		{
			mBluetoothService.disconnect();
			mBluetoothService = null;
		}
	}

	// Android端末のBluetooth機能の有効化要求
	private void requestBluetoothFeature()
	{
		if( mBluetoothAdapter.isEnabled() )
		{
			return;
		}
		// デバイスのBluetooth機能が有効になっていないときは、有効化要求（ダイアログ表示）
		Intent enableBtIntent = new Intent( BluetoothAdapter.ACTION_REQUEST_ENABLE );
		startActivityForResult( enableBtIntent, REQUEST_ENABLEBLUETOOTH );
	}

	// 機能の有効化ダイアログの操作結果
	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data )
	{
		switch( requestCode )
		{
			case REQUEST_ENABLEBLUETOOTH: // Bluetooth有効化要求
				if( Activity.RESULT_CANCELED == resultCode )
				{    // 有効にされなかった
					Toast.makeText( this, R.string.bluetooth_is_not_working, Toast.LENGTH_SHORT ).show();
					finish();    // アプリ終了宣言
					return;
				}
				break;
			case REQUEST_CONNECTDEVICE: // デバイス接続要求
				String strDeviceName;
				if( Activity.RESULT_OK == resultCode )
				{
					// デバイスリストアクティビティからの情報の取得
					strDeviceName = data.getStringExtra( DeviceListActivity.EXTRAS_DEVICE_NAME );
					mDeviceAddress = data.getStringExtra( DeviceListActivity.EXTRAS_DEVICE_ADDRESS );
				}
				else
				{
					strDeviceName = "";
					mDeviceAddress = "";
				}
				( (TextView)findViewById( R.id.textview_devicename ) ).setText( strDeviceName );
				( (TextView)findViewById( R.id.textview_deviceaddress ) ).setText( mDeviceAddress );
				( (TextView)findViewById( R.id.textview_read ) ).setText( "" );
				break;
		}
		super.onActivityResult( requestCode, resultCode, data );
	}

	// オプションメニュー作成時の処理
	@Override
	public boolean onCreateOptionsMenu( Menu menu )
	{
		getMenuInflater().inflate( R.menu.activity_main, menu );
		return true;
	}

	// オプションメニューのアイテム選択時の処理
	@Override
	public boolean onOptionsItemSelected( MenuItem item )
	{
		switch( item.getItemId() )
		{
			case R.id.menuitem_search:
				Intent devicelistactivityIntent = new Intent( this, DeviceListActivity.class );
				startActivityForResult( devicelistactivityIntent, REQUEST_CONNECTDEVICE );
				return true;
		}
		return false;
	}

	@Override
	public void onClick( View v )
	{
		if( mButton_Connect.getId() == v.getId() )
		{
			mButton_Connect.setEnabled( false );    // 接続ボタンの無効化（連打対策）
			connect();            // 接続
			return;
		}
		if( mButton_Disconnect.getId() == v.getId() )
		{
			mButton_Disconnect.setEnabled( false );    // 切断ボタンの無効化（連打対策）
			disconnect();            // 切断
			return;
		}
		if( mButton_WriteHello.getId() == v.getId() )
		{
			mButton_WriteHello.setEnabled( false );    // 文字列送信ボタンの無効化（連打対策）
			mButton_WriteWorld.setEnabled( false );    // 文字列送信ボタンの無効化（連打対策）
			write( "Hello" );
			return;
		}
		if( mButton_WriteWorld.getId() == v.getId() )
		{
			mButton_WriteHello.setEnabled( false );    // 文字列送信ボタンの無効化（連打対策）
			mButton_WriteWorld.setEnabled( false );    // 文字列送信ボタンの無効化（連打対策）
			write( "World" );
			return;
		}
	}

	// 接続
	private void connect()
	{
		if( mDeviceAddress.equals( "" ) )
		{    // DeviceAddressが空の場合は処理しない
			return;
		}

		if( null != mBluetoothService )
		{    // mBluetoothServiceがnullでないなら接続済みか、接続中。
			return;
		}

		// 接続
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice( mDeviceAddress );
		mBluetoothService = new BluetoothService( this, mHandler, device );
		mBluetoothService.connect();
	}

	// 切断
	private void disconnect()
	{
		if( null == mBluetoothService )
		{    // mBluetoothServiceがnullなら切断済みか、切断中。
			return;
		}

		// 切断
		mBluetoothService.disconnect();
		mBluetoothService = null;
	}

	// 文字列送信
	private void write( String string )
	{
		if( null == mBluetoothService )
		{    // mBluetoothServiceがnullなら切断済みか、切断中。
			return;
		}

		// 終端に改行コードを付加
		String stringSend = string + "\r\n";

		// バイト列送信
		mBluetoothService.write( stringSend.getBytes() );
	}
}
