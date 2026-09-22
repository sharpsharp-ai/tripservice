package de.sharpsharp.tripservice;

import java.util.ArrayList;
import java.util.List;

import de.sharpsharp.tripservice.exception.UserNotLoggedInException;
import de.sharpsharp.tripservice.trip.Trip;
import de.sharpsharp.tripservice.trip.TripDAO;
import de.sharpsharp.tripservice.user.User;
import de.sharpsharp.tripservice.user.UserSession;

public class TripService_Original {

    public List<Trip> getTripsByUser(User user) throws UserNotLoggedInException {
        List<Trip> tripList = new ArrayList<Trip>();
        User loggedUser = UserSession.getInstance().getLoggedUser();
        boolean isFriend = false;
        if (loggedUser != null) {
            for (User friend : user.getFriends()) {
                if (friend.equals(loggedUser)) {
                    isFriend = true;
                    break;
                }
            }
            if (isFriend) {
                tripList = TripDAO.findTripsByUser(user);
            }
            return tripList;
        } else {
            throw new UserNotLoggedInException();
        }
    }
    
}
