package cn.iscas.xlab.xbotplayer;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

import cn.iscas.xlab.xbotplayer.mvp.ControlContract;
import cn.iscas.xlab.xbotplayer.ros.ROSClient;
import cn.iscas.xlab.xbotplayer.ros.rosbridge.ROSBridgeClient;
import de.greenrobot.event.EventBus;

/**
 * Created by lisongting on 2017/6/5.
 *
 */

public class RosConnectionService extends Service{

    public static final String TAG = "RosConnectionService";
    private static final String PUBLISH_TOPIC_CONTROL_COMMAND = "/cmd_vel_mux/input/teleop";

    public Binder proxy = new ServiceBinder();
    private ROSBridgeClient rosBridgeClient;

    private boolean isConnected = false;
    private Timer rosConnectionTimer;
    private TimerTask connectionTask;


    public class ServiceBinder extends Binder {
        public boolean isConnected(){
            return isConnected;
        }

        public void publishCommand(Twist twist) {
            if (isConnected()) {
                JSONObject body = new JSONObject();
                JSONArray jsonArray = new JSONArray();
                JSONObject linearMsg = new JSONObject();
                JSONObject angularMsg = new JSONObject();
                try {

                    linearMsg.put("x", twist.getLinear_x());
                    linearMsg.put("y", twist.getLinear_y());
                    linearMsg.put("z", twist.getLinear_z());
                    angularMsg.put("x", twist.getAngular_x());
                    angularMsg.put("y", twist.getAngular_y());
                    angularMsg.put("z", twist.getAngular_z());
                    jsonArray.put(linearMsg);
                    jsonArray.put(angularMsg);

                    body.put("op", "publish");
                    body.put("topic", PUBLISH_TOPIC_CONTROL_COMMAND);

                    JSONObject message = new JSONObject();
                    message.put("angular", angularMsg);
                    message.put("linear", linearMsg);
                    body.put("msg", message);

                    rosBridgeClient.send(body.toString());
                    Log.i(TAG, "publish '/cmd_vel_mux/input/teleop' to Ros Server :\n" + body.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }
        }

        //订阅某个topic或者取消订阅某个topic
        public void manipulateTopic(String topic, boolean isSubscribe) {
            if (isConnected()) {
                //订阅
                if (isSubscribe) {
                    JSONObject subscribeMuseumPos = new JSONObject();
                    try {
                        subscribeMuseumPos.put("op", "subscribe");
                        subscribeMuseumPos.put("topic", topic);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    rosBridgeClient.send(subscribeMuseumPos.toString());

                } else {//取消订阅
                    JSONObject subscribeMuseumPos = new JSONObject();
                    try {
                        subscribeMuseumPos.put("op", "unsubscribe");
                        subscribeMuseumPos.put("topic", topic);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    rosBridgeClient.send(subscribeMuseumPos.toString());
                }
            }
        }

        public void disConnect() {
            connectionTask.cancel();
            rosBridgeClient.disconnect();
//            onDestroy();
        }
    }

    //onCreate()会自动触发Ros服务器的连接
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "RosConnService--onCreate()");
        rosConnectionTimer = new Timer();
        connectionTask = new TimerTask() {
            @Override
            public void run() {
                if (!isConnected) {
                    String rosURL = "ws://" + Config.ROS_SERVER_IP + ":" + Config.ROS_SERVER_PORT;
                    Log.v(TAG, "Connecting to ROS Server: " + rosURL);
                    rosBridgeClient = new ROSBridgeClient(rosURL);
                    boolean conneSucc = rosBridgeClient.connect(new ROSClient.ConnectionStatusListener() {
                        @Override
                        public void onConnect() {
//                            rosBridgeClient.setDebug(true);
                            Log.i(TAG, "Ros ConnectionStatusListener--onConnect");

                        }

                        @Override
                        public void onDisconnect(boolean normal, String reason, int code) {
                            Log.v(TAG, "Ros ConnectionStatusListener--disconnect");
                            Intent broadcastIntent = new Intent(ControlContract.ROS_RECEIVER_INTENTFILTER);
                            Bundle data = new Bundle();
                            data.putInt("ros_conn_status", ControlContract.CONN_ROS_SERVER_ERROR);
                            broadcastIntent.putExtras(data);
                            sendBroadcast(broadcastIntent);
                            isConnected = false;
                        }

                        @Override
                        public void onError(Exception ex) {
                            ex.printStackTrace();
                            Log.i(TAG, "Ros ConnectionStatusListener--ROS communication error");
                        }
                    });
                    isConnected = conneSucc;
                    Intent broadcastIntent = new Intent(ControlContract.ROS_RECEIVER_INTENTFILTER);
                    Bundle data = new Bundle();
                    if (!isConnected) {
                        data.putInt("ros_conn_status", ControlContract.CONN_ROS_SERVER_ERROR);
                        broadcastIntent.putExtras(data);
                        sendBroadcast(broadcastIntent);
                    } else{
                        data.putInt("ros_conn_status", ControlContract.CONN_ROS_SERVER_SUCCESS);
                        broadcastIntent.putExtras(data);
                        sendBroadcast(broadcastIntent);
                    }
                }
            }
        };

        startRosConnectionTimer();
        //注册Eventbus
        EventBus.getDefault().register(this);
    }

    @Override
    public void onRebind(Intent intent) {
        Log.i(TAG, TAG + " -- onRebind()");
        super.onRebind(intent);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "RosConnService--onStartCommand()");
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "RosConnectionService onBind()");

        return proxy;
    }

    //订阅某个topic后，接收到Ros服务器返回的message，回调此方法
    public void onEvent(PublishEvent event) {
        //topic的名称
//        String topicName = event.name;
//        Log.i(TAG, "onEvent:" + event.msg);
//        //Topic为RobotStatus
//        if (topicName.equals(SUBSCRIBE_ROBOT_STATUS)) {
//            String msg = event.msg;
//            JSONObject msgInfo;
//            try {
//                msgInfo = new JSONObject(msg);
//                int id = msgInfo.getInt("id");
//                boolean isMoving = msgInfo.getBoolean("ismoving");
//                RobotStatus robotStatus = new RobotStatus(id, isMoving);
//                EventBus.getDefault().post(robotStatus);
//
//            } catch (JSONException e) {
//                e.printStackTrace();
//            }
//        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "RosConnService--onDestroy()");
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    public void startRosConnectionTimer() {
        if (isConnected) {
            return;
        } else {
            rosConnectionTimer.schedule(connectionTask,0,3000);
        }
    }

}
