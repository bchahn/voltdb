<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<deployment>
    <cluster hostcount="1" kfactor="0" sitesperhost="4"/>
    <ssl/>
    <httpd enabled="true">
        <jsonapi enabled="true"/>
    </httpd>
    <snapshot enabled="false"/>
    <partition-detection enabled="false" />
    <heartbeat timeout="15" />
    <!-- 
    <export>
        <configuration enabled="true" target="ABC" type="file">
              <property name="nonce">ABC</property>
              <property name="type">csv</property>
        </configuration>
    </export>
     -->
    <commandlog enabled="false">
        <frequency/>
    </commandlog>
    <systemsettings>
        <temptables/>
        <snapshot/>
        <elastic/>
        <query/>
        <procedure/>
        <resourcemonitor>
            <memorylimit/>
        </resourcemonitor>
    </systemsettings>
    <security/>
</deployment>
