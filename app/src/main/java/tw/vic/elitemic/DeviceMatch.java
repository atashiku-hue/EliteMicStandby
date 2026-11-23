package tw.vic.elitemic;

import java.util.List;
import java.util.Locale;

final class DeviceMatch {
    static final class Port {
        final int id;
        final String address, name;
        Port(int id, String address, String name){
            this.id=id;
            this.address=address;
            this.name=name;
        }
    }

    static int find(List<Port> connected, int currentId, String previousAddress){
        if(connected.size()==1) return connected.get(0).id;
        for(Port port:connected) if(port.id==currentId) return port.id;
        int jabra=-1, matches=0;
        for(Port port:connected){
            if(!previousAddress.isEmpty() && previousAddress.equalsIgnoreCase(port.address)) return port.id;
            if(port.name.toLowerCase(Locale.ROOT).contains("elite 10")){
                jabra=port.id;
                matches++;
            }
        }
        return matches==1 ? jabra : -1;
    }
}
