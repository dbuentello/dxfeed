package quotail;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.TimerTask;

import com.dxfeed.event.market.Side;
import com.dxfeed.event.market.TimeAndSale;

public class Cluster {
	// maintain the list of trades in chronological order (ascending order of sequences).
	LinkedList<TimeAndSale> trades;
	// This is important for proper categorization
	public int quantity = 0;
	public boolean isSpreadLeg = false;
	public long openinterest = -1;
	public long volume = -1;
	public float money = 0;
	public Side classification;
	public Bin bin;
	public boolean isProcessed = false;
	public long creationTime;
	public Cluster(TimeAndSale t){
		isSpreadLeg = t.isSpreadLeg();
		trades = new LinkedList<TimeAndSale>();
		addTrade(t);
		creationTime = System.currentTimeMillis();
	}
	
	// insert the trade in its proper place based on the sequence field
	public void addTrade(TimeAndSale t){
		if(t.getSize() > 0){
			float d1 = (float)(t.getPrice() - t.getBidPrice());
			float d2 = (float)(t.getAskPrice() - t.getPrice());
			if(Math.abs(d1 - d2) < .001)
				t.setAggressorSide(Side.UNDEFINED);
			else if(d1 < d2)
				t.setAggressorSide(Side.SELL);
			else
				t.setAggressorSide(Side.BUY);

			this.quantity += t.getSize();
			this.money += t.getSize() * t.getPrice() * 100;
			boolean hasInserted = false;
			Iterator<TimeAndSale> it = trades.descendingIterator();
			for(int index = trades.size(); it.hasNext(); --index){
				TimeAndSale lastTrade = it.next();
				if(lastTrade.getSequence() < t.getSequence()){
					trades.add(index, t);
					hasInserted = true;
					break;
				}
			}
			if(!hasInserted){
				trades.add(0, t);
			}
		}
	}
	
	// attempt to find the trade in the internal trades array that matches the parameter. if found, remove and update aggregate information
	public boolean cancelTrade(TimeAndSale t){
		for(int i = 0; i < trades.size(); ++i){
			TimeAndSale tns = trades.get(i);
			if(tns.getSequence() == t.getSequence()){
				// we've found the original trade, squash it
				quantity -= tns.getSize();
				money -= tns.getSize() * t.getPrice() * 100;
				trades.remove(i);
				return true;
			}
		}
		return false;
	}
	
	public boolean correctTrade(TimeAndSale t){
		for(int i = 0; i < trades.size(); ++i){
			TimeAndSale tns = trades.get(i);
			if(tns.getSequence() == t.getSequence()){
				// we've found the original trade,  update it
				quantity = quantity - (int)tns.getSize() + (int)t.getSize();
				money = money - (int)(tns.getSize() * tns.getPrice() * 100) + (int)(t.getSize() * t.getPrice() * 100);
				trades.set(i, t);
				return true;
			}
		}
		return false;		
	}
	
	public void classifyCluster(){
		classification = trades.get(0).getAggressorSide();
		double bid0 = trades.get(0).getBidPrice();
		double ask0 = trades.get(0).getAskPrice();
		double price0 = trades.get(0).getPrice();
		for(int i = 1; i < trades.size(); ++i){
			TimeAndSale trade = trades.get(i);
			if(trade.getAggressorSide() != classification){
				double bid1 = trade.getBidPrice();
				double ask1 = trade.getAskPrice();
				// check if the mid moved up, then a buy
				if((bid1 + ask1) / 2 - (bid0 + ask0) / 2 > .001){
					classification = Side.BUY;
				}
				else if((bid1 + ask1) / 2 - (bid0 + ask0) / 2 < -.001){
					classification = Side.SELL;
				}
				else{
					// if mid stayed the same, see if bid and ask moved up
					if(bid1 > bid0 || ask1 > ask0){
						classification = Side.BUY;
					}
					else if(bid1 < bid0 || ask1 < ask0){
						classification = Side.SELL;
					}
					else{
						//bid and ask stayed the same, so that means price is the only thing that moved
						// if price moved up, it must be a buyer, because that means the trader used up all the best available liquidity at the cheapest price first
						classification = trade.getPrice() > price0 ? Side.BUY : Side.SELL;
					}
				}
			}
		}
	}
	
	public String toJSON(){
		StringBuilder sb = new StringBuilder();
		sb.append("{\"symbol\":");
		sb.append('"' + this.trades.get(0).getEventSymbol() + '"');
		sb.append(",\"qty\":");
		sb.append(this.quantity);
		sb.append(",\"money\":");
		sb.append(this.money);
		sb.append(",\"side\":");
		sb.append(this.classification == Side.BUY ? "\"B\"" : (this.classification == Side.SELL ? "\"S\"" : "\"U\""));
		sb.append(",\"oi\":");
		sb.append(this.openinterest >= 0 ? this.openinterest : null);
		sb.append(",\"volume\":");
		sb.append(this.volume);
		sb.append(",\"time\":");
		TimeAndSale tns = this.trades.get(0);
		sb.append(tns.getTime());
		sb.append(",\"sequence\":");
		sb.append(tns.getSequence());
		sb.append(",\"creationTime\":");
		sb.append(this.creationTime);
		sb.append(",\"bid\":");
		sb.append(tns.getBidPrice());
		sb.append(",\"ask\":");
		sb.append(tns.getAskPrice());
		sb.append(",\"price\":");
		sb.append(tns.getPrice());
		sb.append(",\"type\":");
		sb.append('"' + tns.getType().toString() + '"');
		sb.append(",\"isSpread\":");
		sb.append(tns.isSpreadLeg());
		sb.append(",\"conditions\":");
		sb.append('"' + tns.getExchangeSaleConditions() + '"');
		if(this.trades.size() > 1){
			sb.append(",\"trades\":[");
			for(TimeAndSale t : this.trades){
				sb.append("{\"time\":");
				sb.append(t.getTime());
				sb.append(",\"bid\":");
				sb.append(t.getBidPrice());
				sb.append(",\"ask\":");
				sb.append(t.getAskPrice());
				sb.append(",\"price\":");
				sb.append(t.getPrice());
				sb.append(",\"side\":");
				sb.append(t.getAggressorSide() == Side.BUY ? "\"B\"" : (t.getAggressorSide() == Side.SELL ? "\"S\"" : "\"U\""));
				sb.append(",\"exchange\":");
				sb.append("\"" + t.getExchangeCode() + "\"");
				sb.append(",\"size\":");
				sb.append(t.getSize());
				sb.append(",\"sequence\":");
				sb.append(t.getSequence());
				sb.append("},");
			}
			// remove trailing comma
			sb.deleteCharAt(sb.length() - 1);
			sb.append("]");
		}
		else{
			sb.append(",\"trades\": null");
		}

		sb.append("}");
		return sb.toString();
	}
}