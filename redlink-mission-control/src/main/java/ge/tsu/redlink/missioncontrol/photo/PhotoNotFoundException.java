package ge.tsu.redlink.missioncontrol.photo;

import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;

import java.util.UUID;

class PhotoNotFoundException extends NotFoundException {

    PhotoNotFoundException(UUID id) {
        super(ProblemTypes.PHOTO_NOT_FOUND, "Photo " + id + " does not exist");
    }
}
