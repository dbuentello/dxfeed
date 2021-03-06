/*
 * QDS - Quick Data Signalling Library
 * Copyright (C) 2002-2015 Devexperts LLC
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package com.dxfeed.sample.ondemand;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import com.dxfeed.api.*;
import com.dxfeed.event.market.Quote;
import com.dxfeed.ondemand.OnDemandService;

public class OnDemandSample {
	public static void main(String[] args) throws ParseException, InterruptedException {
		// get on-demand-only data feed
		OnDemandService onDemand = OnDemandService.getInstance();
		DXFeed feed = onDemand.getEndpoint().getFeed();

		// subscribe to Accenture symbol ACN to print its quotes
		DXFeedSubscription<Quote> sub = feed.createSubscription(Quote.class);
		sub.addEventListener(new DXFeedEventListener<Quote>() {
			public void eventsReceived(List<Quote> events) {
				for (Quote quote : events) {
					System.out.println(quote.getEventSymbol() +
						" bid " + quote.getBidPrice() + " /" +
						" ask " + quote.getAskPrice());
				}
			}
		});
		sub.addSymbols("ACN");

		// Watch Accenture drop under $1 on May 6, 2010 "Flashcrash" from 14:47:48 to 14:48:02 EST
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'EST'");
		format.setTimeZone(TimeZone.getTimeZone("America/New_York"));
		Date from = format.parse("2015-05-06 14:47:48 EST");
		Date to = format.parse("2015-05-06 14:48:02 EST");

		// switch into historical on-demand data replay mode
		onDemand.replay(from);

		// replaying events until end time reached
		while (onDemand.getTime().getTime() < to.getTime())              {
			System.out.println("Current state is " + onDemand.getEndpoint().getState() + "," +
				" on-demand time is " + format.format(onDemand.getTime()));
			Thread.sleep(1000);
		}

		// close endpoint completely to release resources and shutdown JVM
		onDemand.getEndpoint().closeAndAwaitTermination();
	}
}
