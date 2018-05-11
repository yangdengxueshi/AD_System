package com.dexin.ad_system.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;

import com.dexin.ad_system.app.AppConfig;
import com.dexin.ad_system.app.CustomApplication;
import com.orhanobut.logger.Logger;
import com.vondear.rxtools.RxNetTool;
import com.vondear.rxtools.view.RxToast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CDRService extends Service {
    private static final String TAG = "TAG_CDRService";
    private final CDRBinder mCDRBinder = new CDRBinder();

    private static class CDRBinder extends Binder {
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        connectToCDRServer();//FIXME 一启动就去连接一下服务器

//        sendTimerDataBroadcastInOnBind();
        initLocalReceiverResourceInOnBind();
        return mCDRBinder;
    }

    @Override
    public void onDestroy() {
        releaseTimerBroadcastResourceInOnDestroy();
        releaseLocalReceiverResourceInOnDestroy();
        {//释放掉Socket所在线程的资源
            releaseSocketThreadResource();
            if (mClientRunnable != null) {
                mClientRunnable.mSocket = null;
                mClientRunnable.mBufferedReader = null;
                mClientRunnable.mOutputStream = null;
                mClientRunnable.mSendHandler = null;
                mClientRunnable = null;
            }
        }
        super.onDestroy();
    }

    //--------------------------------------------------------------------------------------------------------------------------------------------------------
    //---------------------------------------------------------------------TODO Ping并连接CDR服务器-------------------------------------------------------------
    //---------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓-------------------------------------------------------------
    private final Intent mShowPingDialogIntent = new Intent(AppConfig.ACTION_SHOW_PING_DIALOG);
    private final Intent mCancelPingDialogIntent = new Intent(AppConfig.ACTION_CANCEL_PING_DIALOG);

    /**
     * 连接到CDR服务器
     */
    private void connectToCDRServer() {
        if (!RxNetTool.isWifiConnected(CustomApplication.getContext())) {
            RxToast.warning(AppConfig.TIP_CONNECT_TO_WIFI);
            return;
        }
        AppConfig.getLocalBroadcastManager().sendBroadcast(mShowPingDialogIntent);
        new Thread() {//FIXME 不会由程序去主动高频率调用,只会响应用户的"点击事件"
            @Override
            public void run() {
                boolean pingSuccess = false;
                try {
                    pingSuccess = getPingFuture().get();
                } catch (Exception e) {
                    Logger.t(TAG).e(e, "run: ");
                }
                if (pingSuccess) {
                    try {
                        if (mClientRunnable != null) {
                            releaseSocketThreadResource();
                        } else {
                            mClientRunnable = new ClientRunnable();
                        }
                        mClientRunnable.startSubThread();//TODO 2.可以再次开启子线程
                        new Thread(mClientRunnable).start();//FIXME 这里应该以服务器的应答来作为成功与否的标志, 不应该直接通过捕获异常来判断
                    } catch (Exception e) {
                        Logger.t(TAG).e(e, "connectToCDRServer: ");
                    }
                }
                AppConfig.getLocalBroadcastManager().sendBroadcast(mCancelPingDialogIntent.putExtra(AppConfig.KEY_PING_STATUS, pingSuccess));
            }
        }.start();
    }

    //--------------------------------------------------------------------------------------------------------------------------------------------------------
    //---------------------------------------------------------------------TODO 检查网络是否畅通----------------------------------------------------------------
    //---------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓----------------------------------------------------------------
    private final PingCallable mPingCallable = new PingCallable();
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    /**
     * 测试Callable
     */
    private static class PingCallable implements Callable<Boolean> {
        private static final String TAG = "TAG_PingCallable";

        @Override
        public Boolean call() {
            try {
                Process lProcess = Runtime.getRuntime().exec(MessageFormat.format("ping -c 3 -w 100 {0}", AppConfig.mHost));
                if (lProcess.waitFor() == 0) return true;
            } catch (Exception e) {
                Logger.t(TAG).e(e, "isServerAvailable: ");
            }
            return false;
        }
    }

    @NonNull
    private Future<Boolean> getPingFuture() {
        return mExecutorService.submit(mPingCallable);
    }

    //--------------------------------------------------------------------------------------------------------------------------------------------------------
    //---------------------------------------------------------------------FIXME 发送定时广播-------------------------------------------------------------------
    //---------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓--------------------------------------------------------------------
    private static final Handler HANDLER = new Handler();

    private void sendTimerDataBroadcastInOnBind() {
        HANDLER.postDelayed(new Runnable() {
            private final Intent mSendHeartbeatIntent = new Intent(AppConfig.ACTION_SEND_CONFIG_PARAM);
            private final Intent mSendConstellationDiagramIntent = new Intent(AppConfig.ACTION_SHOW_RECEIVED_PARAM);
            private String[] simulationCoordinateArr = {
                    "45F5CE1C0930CF320A0A1F323FC3421F18BD423DE7C00508BF443332F70A33450832D0E5F6BB1DBA3EC0E3091D09BA474409091E09D0B9BA4430F60B31CCE2F7C53CE3BEBBE331E1B94547CFD0BA0B09BBE0E31D4042091DBDF641C0BA46093346BBBBF6BACFCD0ABB1CCFCD1D3142C0C2C020E2E3E115ECCE1DF7E2BAE433463EC00000F82DFEE5232BFC3833BDE21EF80A470BE330BFBF1DE1461E1E09BBF6CF1D1D09F6E2F6CF1D31C13EE3F6BACDE2BC46F6F4B9E21E45BA31BAE231461D45E2E5F7E21E0AE2CDCE45CEF60BE20AF50931B9F6310A1DF61E451EE4BBBA0913EC1DCFC1C0BF41C13FCE09C03F1D33E20A45BBD65123B041411DE241E3DFF6F73232F842BB30E3321E31E347E33FC047B9F7CFBA3314ED0B2E47BAE2B941C11E0BF7E21ECF1E0A45BDE1E20A1DE344E131BBE13234BA30E4CE4042E2BC46BD42C133CFCC46E309CDF63DC4F7BBF632BEBF31CD00F04546F5F7CECEBD460AF7F6BACEE11E093EBF0000262BADAFF5DFFBAEF347E51C1D300CD0D0313E3FCF0B1414E3BCBBB93045E3331FF646D0F61F0A310932311E44E2F6BBF609090AF63109BCE2CEB90A1C4646F6C0C131444445F6BCF71DB90A143EBE410AF7BBD0F745E31EBC32CF33322F1E47BFC1413FBF43F70BC141CFBCF70B481F0000DC17433F33F549C1D0421E1FF91D123DF7BF3F40B90A0A46CEF50A1DE2F5E330D0F6"
                    ,
                    "DE21E1F40A35C5C23DC0BA21203332443230CF1FE21F4532D0D0CE0B0C45F5CFCF344845461C31BA3231413E1ECE44CD09F4EB15F6F70B31CDBB40BFD01DF5E4E3E2E3E3CFF6CF1EBB45F6D0F7CE1E1DBEC00AE245CD0AE21E0ACEBB1DBDB9CFF71EE21E44E2CECE3BC31E0A32BBD00B0000B2B249C3EA12C33AD01837CBCCB720CCF6E2B9F6BB0C1DDF40BFBBE3C0C020453F3D1E323FC0F70BC04146F6ECEBBCD11DCECF20E2B91EE10A09CFCFE3310A471DE3BD43F60A09E1CCCDBBBB1E44BBE20A1C1DE31F3047324646BBE3E332E2CF133AE2E1BA310AF745E4BB321CE30ABD0A32DB15E61F1A4940D3CD4A3FBF1CE11F320C474509F71EC2410A0A464531F5F6F6F4F73FC03F3DC0C2CF30B91F1E343231F63031BBD0F6E2BBBBF6BC1D40C01E44E31E0B3447331DF6E21EE2CFF6F6E344471EC0C0F60AF6090BF6163D0B20F71E0B0BC1C231313231BA1E0AE2451D404044450000000034C6EC39C841F4010CF8E2CDBE1AF5E21DE109E30A4848F7C4EBF61CCF45E346CECECEF508CEE020D044F71DCEE247BAD0BC1E31F7311E451BBC09CCF61DCDE2E2BABA1F2FCFBB34BBD0BB3231B9163BCF1EE345E2E2CDB9CF431D0ACFBE45BB44BC44CFBBBB31E1D0F7F70BBD2F1E33242CF3F2DE06D20B1ECF30F72FE3E431D14744BCECEEE31EBAF941BE1DF6CFD00B0B48BA45CFBFC030E2BC3032E231E2D0F74744"
                    ,
                    "D0CF3145CDBCCF09E233BF40453031BBBBE31E20E21F1FF6464544091E0A0B1DCD0A0ACE32E2143CBDE20A1D0BF3CFB9E3E23E40E209CFE10BF6BA093E403232BACC0933321E31F7F630BC08CFE1470AF709C03F0BCE32F6E244F61CC4C21DCE10E0BC26C6B5044DF60B46354ABB330BE5E1D1E1C1C1D0F7F73209F60A0A3109F547BB09090AF6CEC03FF645D0E2E11D3EC431313209E4443FC0E2F7C03FBB31C0C0C0BFBFBEF61ECFCECE1D1ECEE3E31EBBF6E2E2BB4040E20AC13F0ACF40423246C0C1C1BF3E3FB9CF45E10A1ECFF809E2B31341D845341F47C14143C2E135BCCDCE0AF6431DBD313442450BE3E11EC03EE2333031BBCC4648F545E21D0A1E42411F460A48BEBFCFB92F30CF45BD4533BB44311D45F7E245CE45CFF6F6BBCFE21E403FCF0ACFBA0BE209F7E3CFC1C03B3C4041C0C00AE21FE245E1F6CDF61EBB1F31F6E1E200000000CCDEC12C31F54B3136F44748091CF708BFBE32F6BDCF333143BD1C1FC2133F3F44F741C04140C04146F61E32F7F6F5F61DBCCF0A0B32F6F6C13FD0BBC2C1BFBEC1C109E2CF311D44D0D0E2461E0ABB1EBF41F7CF0BCEE2F64443440AF5F71EF5CF46E1E5320844BB1ECE08F6E3451638E3F14549E422C13C41C147F846F9420CF609F8BBF632BF3FBEE4321FB9E2F51DE3CE450A45F7E2E20A4532E13F3FBB310A32C040BB3109CFD0BC4609D0CFE5BA310944F5"
                    ,
                    "32B9CFBC0ACF43B931F6BAE4BFC1C041311D20470943E2D0153CBC1FBFC0CE09CDF6E109F6F61E1D30431E1C0ACDE3E4F6E3404031F6E31DE3BC0AE1C041BEC032CFF60A1DCEBCE3E3BC33F5CD1F40C018189EEC3721E314DE2D42E8B745E243E31D0BB81DF54808324640C1E2F6BC1DCD09F6F5CF1CC1C10A30BB31E30AE2CDF6E2BA1E3CC3F6CFF71D481D46E1F71E1D1E32431DBC0B47F74530CF09F7BACF2045F4BBBB0B450AF6F70A0B0A1EBAF7BBF71E4744F5E3323C130908E4E3CE1E47F63EFF372048DDBB09E242B9CCCF09414146300AF6CF34F7E21FCA3FBF46E21E1C461CF6F7F7F709CEE5304409ECC3310AF5E3F60ACDF6442F3044CE1E4543BA33BFC00A0A45D04040F5CE45CDE2E2E2CEBC46CFBB0A1E414044E345E2091FBC1D450815EBE133C14044E2E31DF631CFE30A3240C00A1E2200C3D73CDFE6CD0CE347CFBCF44509D031CFBA1FF631E2CFBB0B301EE11D0A15C4441D09F7CDCD341EE24644BB08CE46431E1DBBF5E345D01E1DF632E2CD461E09E432200ACF1E3231CEF7E2CF09BB31CDE2320B4513C5C041BB0A404035E2C2C0C03FE209E3CE1FBD093245E23132BF44FD26F1E6E223CB37F7B81BE3BD3347F547F6D00CF5F7D032F5BEEBED42401E1EF6340A311CD143BA40BEE332E31FCFCEBE440AE2E31DBCF6F744F7E3E2CEBEBF0A1DF61EBFC1D1E4CEF7F7BC46E3403FE3D1BB1D"
                    ,
                    "31E3464943320CBA47E3F5CFC513CF45F744CD31F60B32BC1EE2F5CFBAE2BFC0471EE41D4409F60A0ACE331E461E1C451FBAE4BC4040D0F6CEE3CE4630BC28000DCD1D34ADFFF3442FCF3343CC44CFCE43BF40BDC140E23241C1E330C1C131454509CD474834CECF1D310BBBB847C040F732C13E313215EC31CF31CFBA0AE2F73FC0E31EE21DBBCE0A32E30AF7BCF6E1E2B9CD1FE2CEE3D009CE3109BB0BCFE23209BFBF3132CFB9D0E3F7463D15B8B8BCE4CBF607E14033E3BDC0C242C2E233CFBBE431480D320A30D209CD0A3147E3BD1F41C1E3F6F7F7E2F4CF1C0BF6BD1C3FC2C1BFBF40E1E146470CF5D0F6E44544450A472FBB0ABA4431451DF71E0BE3E2BB321EBFBEE20AE131E209E40A48F53247F8BA1EF6ECC433E230CDE146E147321CE1F6000045E333DEF52FFFD2F5F7E306331FF63141C1BEC0BE4031B940C1451EC0BFF63109E4F6D040C042BF0A1FBC301DF6BBCE1EF71E091E1DE230F6BACFBBCFF7B947C0BF1D310AE0BA1D314609F6BBF6F51E303346BCE2BA31F6E109C314CFE332E20A1F1E45BC32F644E3331E31E2D131CEF71F46100AB7E5E1050A403F40C20B31CFCED0BBE3E221BB1EBD45F7B8F6BA45EBEB0BD21D33E2330A1EE346BCF51FBB1E0AC0BF4042091E1E45E23347F70AE232F631CFCFE30AD0F5BAB931451E1FE1E3BC0BE2CFF5EDC3D045321EE145E3BCE3BAC03EC0BF0A44"
                    ,
                    "BEC309CE1E451CBA34310AE2BC31450B09E2E4BCC0C1F730BAF6C1C01F0A44F6461EE2CF47BABD3145BBE2BE00000000BAF70C2E1C39B9B84536E24645431FBA0C0BE346E2451E0B1CE1E3E3E1B8E2320A1D4531413FB9E145E23344BAE144CFF6CE440ACD33BBBA153E2FBC32B8CDF6F5CF20F6BC32471DC1411E31C240E332C23F1DF609CD45E244E20BE30BF609F6F51DBF412F08C042BA1EC041451DEBEEF13444ED0E21C0423D3CF533334531F61CB9CF1FBD32CEF709CF32E109CF45F644CDF6E230B8BD343FBF0C30CDCFE1F7E21F413E1CD0E30A3FC00AE2F6BCCE31F5B9CEE2D0D01F30E0CEF6F9E0E34140471EBD09333145BD0AF7424209BB470A331C1EF53330C041EC3D47CEF731F746BCBD000040060000D240E31F4AB82EBA310AE2CD1EB93CBDF61ECCBA0A4646BA33F4E431BFC00A0AE13441BEF5E3EC14403E41C00A4645F4CDBB1D0A30E2431DF632C0C0C23EBFC0321EBEBF32B9C040CE1FE21D09F746E2F5D00A4647461D0ACDF7C4C4091CBB31BA32091EBB32E3F6F5301FBB1C47D82CFFCBF3BFC141C4404407F5441FDFCFE34240CF0B09BAE434461BE2F6E443BCE3ECEC451CBAF631E12046BAE4F5CFE21E3F3DE2E345CF3FC130430AF50ACFBB3031F5E11F31BC321D0ABA30443F3F1E32F6F60A0AF5E3C21533B9F7D03D40C2C1F6F6BA45F70AF6BAF61D46BDBCCBE3E000000000DCF8"
                    ,
                    "D04446461FF6BC1CE4D14645E3CECEE3320B1DBCD11F3F4009F6000000004BC00030C241E900C3C50B1C45F6D0B6331FC13DB8CD444547E331CF0A310C080ACFF6F531F7BCF50A1D0B0B1DE3E24632BA451F33461DF61C1C1DD0BC1DEB3CC03EBBBBCFCD090BD044BA0ACEE2E31EF7F51D09F6F646471E0A0ACD450BF50945CE0B4709450B0A32F7BDBB0AE2F70AC04EE3C03E41340D4631F5BD1C1EB9CF301FE44532E146CECEF741BFE2E31D1D45314530BBBC441DCFE33231BB31CFE342CF41BDE1CF47303D3BF6BC33BB0BCEC1BE440B43F63FC00946B94640C20BE3CF31F8BAE20920CE45D01EF6CEE0CEF5C3BFC2C01D0A0BE208421E31C5C1F643BA0940BF000006CCCF3E422FBEF121E333CDBCF4BE09CF464240F7E3BDF6F64733BB1EE232E0E232453141C0321E31E5E1091516BB44E21DF6BA0A0930BBF6BC321D1E46CECDBC1C2E1D3143091DBA0AE20A0A31BAE2F60AE30ACE1E1E1EF6E2E2BAF50AF631E344BFC0BF400A44C141BB1DBBE3B9F8DE13DF033F3C47BD2FE5B74432201CE4E2F4081E32D03031CED041C0F530464833BCB90845483EECBC1C40C0CE1EF7CE33F6321FD045F609CECDD0BCF51DBD1C1D0A1EBB31BB40C0F544474841BF090A46451E1DF6E20BBBBB46F7BAC0BF1D45E2CFF832E0B9F7F7E2E3BC0A44CEBC1E1DE340BF0000C62D30DAD8BDFC1CCF0909CB20E50BF5344842BE"
                    ,
                    "3231441DE3320A0AE30B000000004D351F3EE5B9BA06411E3940CC3434F3F845BC0B43B9CFBBF4BCC0C130CE4240E3E340C04342F51DE30BBBBC30E3CFE2C1411D093EC04730C1400A32BFC031CF3C3C30BAD00BE1F7BAF709BC31F6BD32DFBC46460AF731F7F74532E21EE209F6C0C1CFBCBABCCFBB301E451F1E46E1F117F3EDC91BBE16C8C04341C0BA0A09CF0B32E1351EB90B2FE31ECBE2CFBC4631CDBD33F509E3B820CFE2BBE4413EE3310B0A08D0CFE31BD13FBEC3ED3DC0E2BB0B310A1E45F744BC09D0E1CEF6CE450830BCC1C131F5F71DCE4632E2CE0AE347F8090C1ECF1D47F6F70A1D090B304731ECEC383000004EAD3043C03D3846BA07D3BC47BB47B9E4430D1E331D3EBF1CE1C0C2E1E2403EE1BA40BFE2CECFCFBBF61F313332C042C2C50AF60ACE09BBE1BBE0BAF63243E347BA32E0E309D0441DD0E23231E1BC1ECEE34709441D31CFBB4644F5BA33CF3241411FBAEBC409E1CFE2E3CF20321D1E27230F191809D1F6E0303FC02E0A1E1EBC32E20ACF0ABF441DCD0A1DBC0B1CE2E1BAF61E31F5CFBEE41FEBC4311EF6F60ACDE2CFF6CF1F1E09F6F53240BFCFCF45431E09BBE3E4E3CED145F8BB0931BCBC45C0C0F6E2E346F61F0A44CF1D0B304140BFBEF71E1DE2091EE2311EE2F73031CF0000000035BCB429C140B5DA46B5CD45BAE12ECF332FD03242C03FBF3044BDBFCFF7153DE3F63246"
                    ,
                    "0EDCBD4435BA073145CBD0F5C4C33E41E4E3E3CFF409F745F6E30BBBBB31D1CEF5E3F6E3CFE1BB09473231BC1E1D1FCDBBF5F71F1FBBE11ECF44F6F61D311EF6EB140A44E209BA4547CFBA31C13FCED13F41F61EBE41310A3E40CF09F61EE0BA311EE2CEE11E3220311E40C11207192F2B1C05D1F6F5BFC2B8D11E1EBB08F7E4BA31CF31B9E2E3E3E3F7BE40F6D00CCF1FF6E0461DBC44F70AE21E0BCF32F70AE344F6E1CD3035B9CD091DF815C64143CF42BE40CD1EE2F7090BBBCD4040F60A4708F6D009451EE10A31E4E2F4F7BAE14445BF4047BBBD4446E33248464540C0000000002B02CEB9B91D41303333E10A3244451C414009471FBC45CF31CECEBDF730471EBACDBF40E1321E46F6F6BB311F32E21D0A0AC3EB3FC0310ABF4046F60AE21E1ECDCDCFF61EE23244BC45E3E10A44C0401FCE3F41F60ABF41F6D0BCCEE14632E2E2B9BB31450AC140450AE3F9E3D2F2B231BBCD37C03EC1BCB942CFF40B09CFC93F42D02FE2E3E3BBE2CECDF6D0E2BA090BCFCF09081E43BE1FE3F6F6C414F61C42431EBA46E340C03245E2CE1D1DBB1E47CD44453130F64646BB0A453F401E4732E2CEE0E2F5BCE2C0BF31BBE30941C0C4141EF63109BB334140BDE4471D1F45000040B8DCDA3E2CC02DE21635E2B9F6E7F4F5B7BCE0B9CCBB44F7F6F7BC311EBA1DCE421DF740C03E3EC040CDE1404144D0E10B09E11D1DCF32"
                    ,
                    "BACECB48E231311CBCF6BBCFF5B9E2E33F3F1EE11E0AF6090BF51DCDBB1FF609CECF31B9D01DCF0A42BCBF42F6BB45E13346F6BBED3CE2E41EF731E331CE46E3F631E1BAF631471E4732300A0AF5BC46D04546F6BA1ED045E1BB0B1FC9E3E750F347D11DBD3C4248D0E33B3C091D45F6BA0CBBF8450841BE0AE2F4BCCE1D1F32F5BDE332BBE6F846CDE5CCE1BFC1C0401CCE2043F61DBC440C33F4BC1E3214C4D13243411DCFB9D0324731F7CF310A32D1E4443231CD30BDE2F6320AC041C0C144E231CD3910F8F6301DCFF6BC0A1810D820323248C7D1CAAC403E49D4BB08F81E0A44F7B91C09E146CE300ACB09CE3431F5E20820CE41403109451DCFCEF809BBE31D1EE0343031E2E1ECED4533E233F51EB9320B440D4432BAF632BBD0CD0B44433144CE0BDFCE4631BC46F6F645CEBAE2091DE2BCBBF6F5BBE2F8B91E3DEA4545D158463B1FC7DE0B1FCFCF42CFBB43DFB933E20CE3D0BC0CE0D03EBF31CF32F5CDE41F3231CC1E440A31E3D0E21DE50C0B443F3CE1BDE445BBF631BBE11F46B8BEC144E208CD40400A43E3E3E20A30F51E31E2BC1D0A3232BBF6F71E32BBF844BFC1C0C11E43BBF7EC3ECB4843450B43094441C10000D040EC30D0F149BD0844B5B4BB32BBE2BE4444CE1F303009CFE3BB32BD450BCFCF34F645F5BC1613CF4709B83232CF0BE4E3441EBBF60931BAB9321DF61DE3F543D0E209CDF7"
            };
            private int i = 0;

            @Override
            public void run() {
//                {//向服务器发数据          "syn_state=1&rf_power=2164646444546&cnr=35646444566&mer=164686456688&freq_offset=53558646646&clk_offset=5215349498&msc_qam=108000000123456&cic_qam=20165644988&ldpc_cr=10164468635\n"
//                    mSendHeartbeatIntent.putExtra(AppConfig.KEY_CONFIG_PARAM, "syn_state=1&rf_power=2164646444546&cnr=35646444566&mer=164686456688&freq_offset=53558646646&clk_offset=5215349498&msc_qam=108000000123456&cic_qam=20165644988&ldpc_cr=10164468635\n");
//                    AppConfig.getLocalBroadcastManager().sendBroadcast(mSendHeartbeatIntent);
//                    HANDLER.postDelayed(this, 5L * 1000L);
//                }

//                {//向自己发送数据          "syn_state=1&rf_power=2164646444546&cnr=35646444566&mer=164686456688&freq_offset=53558646646&clk_offset=5215349498&msc_qam=108000000123456&cic_qam=20165644988&ldpc_cr=10164468635\n"
//                    mSendConstellationDiagramIntent.putExtra(AppConfig.KEY_RECEIVED_DATA, "syn_state=1&rf_power=2164646444546&cnr=35646444566&mer=164686456688&freq_offset=53558646646&clk_offset=5215349498&msc_qam=108000000123456&cic_qam=20165644988&ldpc_cr=10164468635");
//                    AppConfig.getLocalBroadcastManager().sendBroadcast(mSendConstellationDiagramIntent);
//                    HANDLER.postDelayed(this, 5L * 1000L);
//                }

                {//向自己发送数据
                    mSendConstellationDiagramIntent.putExtra(AppConfig.KEY_RECEIVED_DATA, MessageFormat.format("scatt={0}", simulationCoordinateArr[i % simulationCoordinateArr.length]));
                    AppConfig.getLocalBroadcastManager().sendBroadcast(mSendConstellationDiagramIntent);
                    HANDLER.postDelayed(this, 5L * 1000L);
                }
                i++;
            }
        }, 5L * 1000L);
    }

    private static void releaseTimerBroadcastResourceInOnDestroy() {
        HANDLER.removeCallbacksAndMessages(null);
    }

    //--------------------------------------------------------------------------------------------------------------------------------------------------------
    //---------------------------------------------------------------------FIXME 本地广播模块-------------------------------------------------------------------
    //---------------------------------------------------------------------↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓--------------------------------------------------------------------
    private static final int HANDLE_SEND_MESSAGE = 22;
    private LocalReceiver mLocalReceiver;
    private IntentFilter mIntentFilter;

    private void initLocalReceiverResourceInOnBind() {
        mLocalReceiver = new LocalReceiver();
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(AppConfig.ACTION_CONNECT_TO_CDR_SERVER);
        mIntentFilter.addAction(AppConfig.ACTION_SEND_CONFIG_PARAM);
        AppConfig.getLocalBroadcastManager().registerReceiver(mLocalReceiver, mIntentFilter);
    }

    private void releaseLocalReceiverResourceInOnDestroy() {
        AppConfig.getLocalBroadcastManager().unregisterReceiver(mLocalReceiver);
        mLocalReceiver = null;
        mIntentFilter = null;
    }

    private class LocalReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {//广播接收器
            if (AppConfig.isComponentAlive(CDRService.this)) {
                switch (Objects.requireNonNull(intent.getAction())) {
                    case AppConfig.ACTION_CONNECT_TO_CDR_SERVER:
                        connectToCDRServer();
                        break;
                    case AppConfig.ACTION_SEND_CONFIG_PARAM://TODO 向网络写入数据: intent.getStringExtra(AppConfig.KEY_CONFIG_PARAM)
                        Message lMessage = new Message();
                        lMessage.what = HANDLE_SEND_MESSAGE;
                        lMessage.obj = intent.getStringExtra(AppConfig.KEY_CONFIG_PARAM);
                        if ((mClientRunnable != null) && (mClientRunnable.mSendHandler != null)) {
                            mClientRunnable.mSendHandler.sendMessage(lMessage);
                        }
                        break;
                    default:
                }
            }
        }
    }

    //------------------------------------------------------------------------------------------------------------------------------------------------------
    //-------------------------------------------------FIXME Socket线程模块----------------------------------------------------------------------------------
    //------------------------------------------------------------------------------------------------------------------------------------------------------
    private ClientRunnable mClientRunnable;// 定义与服务器通信的子线程

    /**
     * 释放掉Socket所在线程的资源
     */
    private void releaseSocketThreadResource() {
        try {
            if (mClientRunnable != null) {
                mClientRunnable.cancelSubThread();//TODO 1.取消掉子线程
                if (mClientRunnable.mSocket != null) {
                    mClientRunnable.mSocket.close();
                }
                if (mClientRunnable.mBufferedReader != null) {
                    mClientRunnable.mBufferedReader.close();
                }
                if (mClientRunnable.mOutputStream != null) {
                    mClientRunnable.mOutputStream.close();
                }
                if (mClientRunnable.mSendHandler != null) {
                    mClientRunnable.mSendHandler.removeCallbacksAndMessages(null);
                }
            }
        } catch (IOException e) {
            Logger.t(TAG).e(e, "onDestroy: ");
        }
    }

    private final class ClientRunnable implements Runnable {
        private Socket mSocket;
        private BufferedReader mBufferedReader;//定义该线程所处的Socket所对应的输入流
        private OutputStream mOutputStream;
        private Handler mSendHandler;//定义"发送消息"的Handler对象
        private final Intent mShowReceivedParamIntent = new Intent(AppConfig.ACTION_SHOW_RECEIVED_PARAM);//TODO 用来发送"待显示的数据"

        private volatile boolean mSubThreadRunOn = true;     //TODO 所有与其相关的线程都会感知他的变化   (子线程运行中...)

        void startSubThread() {
            mSubThreadRunOn = true;//当前线程的子线程 可以运行
        }

        void cancelSubThread() {
            mSubThreadRunOn = false;//安全终止当前线程的子线程
        }

        @Override
        public void run() {
            try {
                mSocket = new Socket(AppConfig.mHost, AppConfig.mPort);
                mBufferedReader = new BufferedReader(new InputStreamReader(mSocket.getInputStream(), AppConfig.UTF_8_CHAR_SET));//TODO 网络输入流
                mOutputStream = mSocket.getOutputStream();//TODO 网络输出流

                new Thread() {//新开子线程 并 用死循环 去监听输入流上的数据
                    @Override
                    public void run() {
                        try {
                            String content;
                            while (mSubThreadRunOn && ((content = mBufferedReader.readLine()) != null)) {
                                Logger.t(TAG).d("接收到的服务端数据AAAAAAAAAAAAAAAAA:" + content);
                                AppConfig.getLocalBroadcastManager().sendBroadcast(mShowReceivedParamIntent.putExtra(AppConfig.KEY_RECEIVED_DATA, content));
                            }
                        } catch (Exception e) {
                            Logger.t(TAG).e(e, "run: ");
                        }
                    }
                }.start();

                {//FIXME 将mSendHandler放在子线程进行"网络写操作"
                    Looper.prepare();
                    mSendHandler = new Handler() {
                        @Override
                        public void handleMessage(Message msg) {
                            switch (msg.what) {
                                case HANDLE_SEND_MESSAGE:
                                    try {
                                        mOutputStream.write(msg.obj.toString().getBytes(AppConfig.UTF_8_CHAR_SET));
                                    } catch (IOException e) {
                                        Logger.t(TAG).e(e, "handleMessage: ");
                                    }
                                    break;
                                default:
                            }

                        }
                    };
                    Looper.loop();
                }
            } catch (Exception e) {
                Logger.t(TAG).e(e, "run: ");
            }
        }
    }
}
