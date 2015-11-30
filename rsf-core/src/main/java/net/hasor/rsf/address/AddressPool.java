/*
 * Copyright 2008-2009 the original 赵永春(zyc@hasor.net).
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
package net.hasor.rsf.address;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.route.flowcontrol.random.RandomFlowControl;
import net.hasor.rsf.address.route.flowcontrol.speed.SpeedFlowControl;
import net.hasor.rsf.address.route.flowcontrol.unit.UnitFlowControl;
import net.hasor.rsf.address.route.rule.ArgsKey;
import net.hasor.rsf.address.route.rule.DefaultArgsKey;
import net.hasor.rsf.address.route.rule.Rule;
import net.hasor.rsf.address.route.rule.RuleParser;
import net.hasor.rsf.utils.RsfRuntimeUtils;
import org.more.builder.ReflectionToStringBuilder;
import org.more.builder.ToStringStyle;
import org.more.util.ExceptionUtils;
import org.more.util.StringUtils;
import org.more.util.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * 服务地址池
     * <p>路由策略：
     * 随机选址
     * 
     * 流控规则
     *  服务级
     *  
     *  方法级
     *  
     *  参数级
     * 
     * 
     * 
     * 路由规则

 * @version : 2014年9月12日
 * @author 赵永春(zyc@hasor.net)
 */
public class AddressPool {
    private static final String                        CharsetName  = "UTF-8";
    private static final String                        ScriptPath   = "/script";
    private static final String                        SnapshotPath = "/snapshot";
    //
    protected Logger                                   logger       = LoggerFactory.getLogger(getClass());
    private AtomicBoolean                              inited       = new AtomicBoolean(false);
    private final File                                 rsfHome;
    private final File                                 indexFile;
    private final File                                 scriptHome;
    private final File                                 snapshotHome;
    //
    private final RsfEnvironment                       rsfEnvironment;
    private final ConcurrentMap<String, AddressBucket> addressPool;                                       //服务地址池Map.
    private final String                               unitName;                                          //本机所处单元.
    //
    private final AddressCacheResult                   rulerCache;
    private final RuleParser                           ruleParser;
    private final ArgsKey                              argsKey;
    private volatile InnerFlowControlRef               flowControlRef;                                    //默认流控规则引用
    private volatile InnerScriptResourceRef            scriptResourcesRef;
    private final Object                               poolLock;
    private final Thread                               timer;
    //
    //
    class PoolThread implements Runnable {
        public void run() {
            RsfSettings rsfSettings = rsfEnvironment.getSettings();
            long refreshCacheTime = rsfSettings.getRefreshCacheTime();
            long nextCheckSavePoint = 0;
            logger.info("AddressPool - refreshCache.", refreshCacheTime);
            readAddress();//当启动时，进行一次恢复。
            while (true) {
                try {
                    Thread.sleep(refreshCacheTime);
                } catch (InterruptedException e) {
                    /**/
                }
                logger.info("AddressPool - refreshCache. at = ", refreshCacheTime);
                refreshCache();
                if (rsfSettings.islocalDiskCache() && nextCheckSavePoint < System.currentTimeMillis()) {
                    nextCheckSavePoint = System.currentTimeMillis() + (1 * 60 * 60 * 1000);//1小时
                    try {
                        saveAddress();
                    } catch (IOException e) {
                        logger.error("saveAddress error {} -> {}", e.getMessage(), e);
                    }
                }
            }
        }
    }
    //
    /**保存地址列表到zip流中(每小时保存一次)。*/
    protected void saveAddress() throws IOException {
        File writeFile = new File(this.snapshotHome, "address-" + System.currentTimeMillis() + ".zip");
        logger.info("rsf - saveAddress to snapshot file({}) ->{}", CharsetName, writeFile);
        try {
            writeFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(writeFile);
            ZipOutputStream zipStream = new ZipOutputStream(fos);
            synchronized (this.poolLock) {
                for (AddressBucket bucket : this.addressPool.values()) {
                    if (bucket != null) {
                        logger.debug("rsf - service saveAddress {} storage to snapshot file ->{}", bucket.getServiceID(), writeFile);
                        bucket.saveTo(zipStream, CharsetName);
                    }
                }
            }
            zipStream.flush();
            zipStream.close();
            //
            FileWriter fw = new FileWriter(this.indexFile, false);
            logger.info("rsf - update snapshot index -> " + this.indexFile.getAbsolutePath());
            fw.write(writeFile.getName());
            fw.flush();
            fw.close();
        } catch (IOException e) {
            logger.error("rsf - saveAddress IOException :" + e.getMessage(), e);
            throw e;
        }
    }
    /**从保存的地址本中恢复数据。*/
    protected void readAddress() {
        //1.校验
        if (!this.indexFile.exists()) {
            logger.info("address snapshot index file, undefined.");
            return;
        }
        if (!this.indexFile.canRead()) {
            logger.error("address snapshot index file, can not read.");
            return;
        }
        //2.确定要读取的文件。
        File readFile = null;
        try {
            String index = FileUtils.readFileToString(this.indexFile, CharsetName);
            readFile = new File(this.snapshotHome, index);
            if (StringUtils.equals(index, "") || !readFile.exists()) {
                logger.error("address snapshot file is not exist.", readFile);
                return;
            }
        } catch (Throwable e) {
            logger.error("read the snapshot file name error :" + e.getMessage(), e);
            return;
        }
        //3.读取地址。
        Collection<AddressBucket> buckerColl = addressPool.values();
        for (AddressBucket bucker : buckerColl) {
            if (bucker == null) {
                continue;
            }
            try {
                ZipFile zipFile = new ZipFile(readFile);
                bucker.readFrom(zipFile, CharsetName);
                zipFile.close();
            } catch (Throwable e) {
                logger.error("addressBucket read snapshot file error :" + e.getMessage(), e);
            }
        }
    }
    //
    public void init() {
        if (inited.compareAndSet(false, true)) {
            this.rulerCache.reset();
            logger.info("start address snapshot Thread[{}].", timer.getName());
            timer.start();
        } else {
            throw new IllegalStateException("AddressPool has been initialized");
        }
    }
    //
    public AddressPool(RsfEnvironment rsfEnvironment) {
        String unitName = rsfEnvironment.getSettings().getUnitName();
        logger.info("init AddressPool unitName = " + unitName);
        //
        this.rsfEnvironment = rsfEnvironment;
        this.rsfHome = new File(rsfEnvironment.evalString("%" + RsfEnvironment.WORK_HOME + "%/rsf/"));
        this.scriptHome = new File(rsfHome, ScriptPath);
        this.snapshotHome = new File(rsfHome, SnapshotPath);
        this.indexFile = new File(snapshotHome, "address.index");
        //
        RsfSettings rsfSettings = rsfEnvironment.getSettings();
        this.addressPool = new ConcurrentHashMap<String, AddressBucket>();
        this.unitName = unitName;
        this.rulerCache = new AddressCacheResult(this);
        this.ruleParser = new RuleParser(rsfSettings);
        this.poolLock = new Object();
        this.timer = new Thread(new PoolThread());
        this.timer.setName("RSF-AddressPool-RefreshCache-Thread");
        this.timer.setDaemon(true);
        this.flowControlRef = InnerFlowControlRef.defaultRef(rsfSettings);
        //
        String argsKeyType = rsfSettings.getString("hasor.rsfConfig.route.argsKey", DefaultArgsKey.class.getName());
        logger.info("argsKey type is {}", argsKeyType);
        try {
            Class<?> type = Class.forName(argsKeyType);
            this.argsKey = (ArgsKey) type.newInstance();
        } catch (Throwable e) {
            logger.error("create argsKey " + argsKeyType + " , message = " + e.getMessage(), e);
            throw ExceptionUtils.toRuntimeException(e);
        }
        //
        this.scriptResourcesRef = new InnerScriptResourceRef();
    }
    //
    /**
     * 获取本机所属单元（虚机房 or 集群）
     * @return 返回单元名（虚机房 or 集群）
     */
    public String getUnitName() {
        return this.unitName;
    }
    /**
     * 所有服务地址快照功能，该接口获得的数据不可以进行写操作。通过这个接口可以获得到此刻地址池中所有服务的：
     * <ol>
     * <li>原始服务地址列表，以{serviceID}_ALL作为key</li>
     * <li>本单元服务地址列表，以{serviceID}_UNIT作为key</li>
     * <li>不可用服务地址列表，以{serviceID}_INVALID作为key</li>
     * <li>所有可用服务地址列表，以{serviceID}作为key</li>
     * <ol>
     * 并不是单元化的列表中是单元化规则计算的结果,规则如果失效单元化列表中讲等同于 all
     */
    public Map<String, List<InterAddress>> allServiceAddressToSnapshot() {
        Map<String, List<InterAddress>> snapshot = new HashMap<String, List<InterAddress>>();
        synchronized (this.poolLock) {
            for (String key : this.addressPool.keySet()) {
                AddressBucket bucket = this.addressPool.get(key);
                snapshot.put(key + "_ALL", bucket.getAllAddresses());
                snapshot.put(key + "_UNIT", bucket.getLocalUnitAddresses());
                snapshot.put(key + "_INVALID", bucket.getInvalidAddresses());
                snapshot.put(key, bucket.getAvailableAddresses());
            }
        }
        return snapshot;
    }
    /**
     * 获取地址池中注册的服务列表。
     * @see net.hasor.rsf.RsfBindInfo#getBindID()
     * @return 返回地址池中注册的服务列表。
     */
    public Collection<String> listServices() {
        Set<String> duplicate = new HashSet<String>();
        synchronized (this.poolLock) {
            duplicate.addAll(this.addressPool.keySet());
        }
        return duplicate;
    }
    //
    //
    /**
     * 新增或追加更新服务地址信息。<p>
     * 如果追加的地址是已存在的失效地址，那么updateAddress方法将重新激活这些失效地址。
     * 
     * @param serviceID 服务ID。
     * @param newHostList 追加更新的地址。
     */
    public void updateAddress(String serviceID, Collection<URI> newHostList) {
        String hosts = ReflectionToStringBuilder.toString(newHostList, ToStringStyle.SIMPLE_STYLE);
        logger.info("updateAddress of service {} , new Address set = {} ", serviceID, hosts);
        //1.AddressBucket
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            /*在并发情况下,invalidAddress可能正打算读取AddressBucket,因此要锁住poolLock*/
            synchronized (this.poolLock) {
                AddressBucket newBucket = new AddressBucket(serviceID, this.unitName);
                bucket = this.addressPool.putIfAbsent(serviceID, newBucket);
                if (bucket == null) {
                    bucket = newBucket;
                }
                logger.info("newBucket {}", bucket);
            }
        }
        //2.新增服务
        logger.info("update service {} address -> {}.", serviceID, newHostList);
        bucket.newAddress(newHostList);
        bucket.refreshAddress();//局部更新
        this.rulerCache.reset();
    }
    //
    /**
     * 将服务的地址设置成临时失效状态。在{@link net.hasor.rsf.RsfSettings#getInvalidWaitTime()}毫秒之后，失效的地址会重新被列入备选地址池。
     * 置为失效，失效并不意味着永久的。在如果该地址同时有多个服务使用同一个地址，则需要依次执行失效。
     * @param serviceID 服务ID。
     * @param invalidAddress 失效的地址。
     */
    public void invalidAddress(String serviceID, InterAddress invalidAddress) {
        long invalidWaitTime = rsfEnvironment.getSettings().getInvalidWaitTime();
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            logger.info("serviceID ={} ,invalid address = {} ,bucket is not exist.", serviceID, invalidAddress);
            return;
        }
        logger.info("serviceID ={} ,invalid address = {} ,wait {} -> active.", serviceID, invalidAddress, invalidWaitTime);
        bucket.invalidAddress(invalidAddress, invalidWaitTime);
        bucket.refreshAddress();
        this.rulerCache.reset();
    }
    /**
     * 将服务的地址设置成临时失效状态，把地址从服务的地址本中彻底删除。
     * @param serviceID 服务ID。
     * @param address 将要删除的地址。
     */
    public void removeAddress(String serviceID, InterAddress address) {
        long invalidWaitTime = rsfEnvironment.getSettings().getInvalidWaitTime();
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            logger.info("serviceID ={} ,remove address = {} ,bucket is not exist.", serviceID, address);
            return;
        }
        logger.info("serviceID ={} ,remove address = {} ,wait {} -> active.", serviceID, address, invalidWaitTime);
        bucket.invalidAddress(address, invalidWaitTime);
        bucket.refreshAddress();
        this.rulerCache.reset();
    }
    /**
     * 从地址池中，删除指定服务的地址本。
     * @param serviceID 服务ID。
     */
    public void removeBucket(String serviceID) {
        logger.info("removeAddressBucket serviceID is {}", serviceID);
        this.addressPool.remove(serviceID);
        this.rulerCache.reset();
    }
    //
    /**
     * 保存规则脚本数据到规则快照目录，当下一次启动RSF的时。将尝试从本地加载地址本而非远程注册中心。
     * @param name 规则名。
     * @param script 规则脚本。
     */
    protected boolean saveScript(String name, String script) {
        String fileName = name + "-" + RsfRuntimeUtils.nowTime() + ".rol";
        File rolFileName = new File(this.scriptHome, fileName);
        //
        try {
            FileUtils.write(rolFileName, script, CharsetName);
            logger.info("save the rule script to {}", rolFileName);
            return true;
        } catch (IOException e) {
            logger.error("save the rule script to " + rolFileName + " ,error : " + e.getMessage(), e);
            return false;
        }
    }
    /**
     * 更新默认流控规则。
     * @param flowControl 流控规则
     */
    public void refreshDefaultFlowControl(String flowControl) {
        String fileName = "flowControl-default";
        logger.info("refreshDefaultFlowControl ,save snapshots status = ", saveScript(fileName, flowControl));
        //
        InnerFlowControlRef flowControlRef = paselowControl(flowControl);
        this.flowControlRef = flowControlRef;
        this.refreshCache();
    }
    //
    /**
     * 更新服务的流控规则。
     * @param serviceID 应用到的服务。
     * @param flowControl 流控规则
     */
    public void refreshFlowControl(String serviceID, String flowControl) {
        String fileName = "flowControl-" + serviceID;
        logger.info("refreshFlowControl ,save snapshots status = ", saveScript(fileName, flowControl));
        //
        if (!StringUtils.isBlank(serviceID)) {
            AddressBucket bucket = this.addressPool.get(serviceID);
            if (bucket != null) {
                logger.info("service {} refreshFlowControl.", serviceID);
                InnerFlowControlRef flowControlRef = paselowControl(flowControl);
                bucket.setFlowControlRef(flowControlRef);
                this.refreshCache();
            }
        }
    }
    /**
     * 更新默认路由规则。
     * @param scriptType 要更新的路由规则脚本类型。
     * @param script 路由规则脚本内容。
     */
    public void refreshDefaultRouteScript(RouteScriptTypeEnum scriptType, String script) throws IOException {
        String fileName = "routeScript-" + scriptType.name() + "-default";
        logger.info("refreshDefaultRouteScript ,save snapshots status = ", saveScript(fileName, script));
        //
        InnerScriptResourceRef resource = new InnerScriptResourceRef(this.scriptResourcesRef);
        RouteScriptTypeEnum.updateScript(scriptType, script, resource);
        this.scriptResourcesRef = resource;
        this.refreshCache();
    }
    /**
     * 更新某个服务的路由规则脚本。
     * @param serviceID 要更新的服务。
     * @param scriptType 要更新的路由规则脚本类型。
     * @param script 路由规则脚本内容。
     */
    public void refreshRouteScript(String serviceID, RouteScriptTypeEnum scriptType, String script) throws IOException {
        String fileName = "routeScript-" + scriptType.name() + "-" + serviceID;
        logger.info("refreshRouteScript ,save snapshots status = ", saveScript(fileName, script));
        //
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket != null) {
            logger.info("service {} refreshRouteScript.", serviceID);
            InnerScriptResourceRef resource = new InnerScriptResourceRef(bucket.getScriptResourcesRef());
            RouteScriptTypeEnum.updateScript(scriptType, script, resource);
            bucket.setScriptResourcesRef(resource);
            this.refreshCache();
        }
    }
    /**
     * 解析路由规则。
     * @param flowControl 规则配置
     * @return 返回解析的结果。
     */
    private InnerFlowControlRef paselowControl(String flowControl) {
        if (StringUtils.isBlank(flowControl) || !flowControl.startsWith("<controlSet") || !flowControl.endsWith("</controlSet>")) {
            logger.error("flowControl body format error.");
            return null;
        }
        //
        RsfSettings rsfSettings = rsfEnvironment.getSettings();
        InnerFlowControlRef flowControlRef = InnerFlowControlRef.defaultRef(rsfSettings);
        //
        //1.提取路由配置
        List<String> ruleBodyList = new ArrayList<String>();
        final String tagNameBegin = "<flowControl";
        final String tagNameEnd = "</flowControl>";
        int beginIndex = 0;
        int endIndex = 0;
        while (true) {
            beginIndex = flowControl.indexOf(tagNameBegin, endIndex);
            endIndex = flowControl.indexOf(tagNameEnd, endIndex + tagNameEnd.length());
            if (beginIndex < 0 || endIndex < 0) {
                break;
            }
            String flowControlBody = flowControl.substring(beginIndex, endIndex + tagNameEnd.length());
            ruleBodyList.add(flowControlBody);
        }
        if (ruleBodyList.isEmpty()) {
            logger.warn("flowControl is empty -> use default settings.");
        }
        //2.解析路由配置
        for (int i = 0; i < ruleBodyList.size(); i++) {
            String controlBody = ruleBodyList.get(i);
            Rule rule = this.ruleParser.ruleSettings(controlBody);
            if (rule == null) {
                continue;
            }
            String simpleName = rule.getClass().getSimpleName();
            logger.info("setup flowControl -> {}.", simpleName);
            /*  */if (rule instanceof UnitFlowControl) {
                flowControlRef.unitFlowControl = (UnitFlowControl) rule; /*单元规则*/
            } else if (rule instanceof RandomFlowControl) {
                flowControlRef.randomFlowControl = (RandomFlowControl) rule;/*选址规则*/
            } else if (rule instanceof SpeedFlowControl) {
                flowControlRef.speedFlowControl = (SpeedFlowControl) rule; /*速率规则*/
            }
        }
        //3.引用切换
        return flowControlRef;
    }
    //
    /**刷新缓存*/
    public void refreshCache() {
        /*在并发情况下,newAddress和invalidAddress可能正在执行,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            Set<String> keySet = this.addressPool.keySet();
            for (String bucketKey : keySet) {
                logger.info("service {} refreshCache.", bucketKey);
                this.addressPool.get(bucketKey).refreshAddress();//刷新地址计算结果
            }
            this.rulerCache.reset();
        }
    }
    //
    /**
     * 从服务地址本中获取一条可用的地址。<p>当一个服务具有多个地址的情况下，为了保证公平性地址池采取了随机选取的方式（路由策略：随机选址）
     * <ul>
     *  <li>如果地址池中没有定义这个服务的Bucket，那么将会返回一个null。</li>
     *  <li>如果地址本或者地址池上配置了流控机制，那么选择到的地址将会被限制固定的速率，进而限制nextAddress方法的整个QPS。</li>
     *  <li>默认情况下地址的获取，会受到路由规则、流控规则的影响。</li>
     * </ul>
     * 当地址获取和地址更新同时进行时候，不需要保证瞬时的一致性，只要保证最终一致性就好。
     * @param serviceID 服务id
     * @param methodSign 调用该服务的方法签名
     * @param args 方法调用时用到的参数。
     * @return 返回可以使用的地址。
     */
    public InterAddress nextAddress(String serviceID, String methodSign, Object[] args) {
        AddressBucket bucket = addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        //
        List<InterAddress> addresses = this.rulerCache.getAddressList(serviceID, methodSign, args);
        InterAddress doCallAddress = null;
        //
        /*并发下不需要保证瞬时的一致性,只要保证最终一致性就好.*/
        InnerFlowControlRef flowControlRef = bucket.getFlowControlRef();
        if (flowControlRef == null) {
            flowControlRef = this.flowControlRef;
        }
        doCallAddress = flowControlRef.randomFlowControl.getServiceAddress(addresses);
        while (true) {
            boolean check = flowControlRef.speedFlowControl.callCheck(serviceID, methodSign, doCallAddress);//QoS
            if (check) {
                break;
            }
        }
        //
        return doCallAddress;
    }
    //
    protected ArgsKey getArgsKey() {
        return this.argsKey;
    }
    protected InnerScriptResourceRef getScriptResources(String serviceID) {
        AddressBucket bucket = this.addressPool.get(serviceID);
        InnerScriptResourceRef script = this.scriptResourcesRef;
        if (bucket != null && bucket.getScriptResourcesRef() != null) {
            script = bucket.getScriptResourcesRef();
        }
        return script;
    }
    @Override
    public String toString() {
        return "AddressPool[" + this.unitName + "]";
    }
}
